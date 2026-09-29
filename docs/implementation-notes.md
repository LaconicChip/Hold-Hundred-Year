# 实现备忘（冻结决策 · 机制速查 · 运维 · 验收）

> 本文件是**实现层的唯一备忘**：设计意图看 [spec.md](spec.md)，怎么落地、哪些不能动、坑在哪看这里。
> 规则冲突时优先级：**设计规格 > 本备忘 > 其他**。
> 它取代了此前散落的《实现计划》（Phase 0-13 已全部完成，任务清单已无参考价值）与两份窗口交接文档。

## 1. 设计阶段确认的痛点与结论（P1-P15，实现时不可回退）

| # | 痛点 | 结论/约束 |
| --- | --- | --- |
| P1 | 节奏太慢 | 1 天 = 90 跳（上午 45 → 午休 → 下午 45）；跳数与波动标定不变，墙钟由"3 秒/跳 × 108 天 ≈ 9 小时"压到"1 秒/跳 × 72 天 ≈ 2 小时"（2026-09-29） |
| P2 | 纯随机无叙事、纯剧本太公式 | 11 纪元顺序与全覆盖硬保证，边界 ±1~3 天抖动、强度区间随机，注册时掷点入库 |
| P3 | 随机游走不像股市 | 三层因子（市场/板块/个股 β）+ GARCH-lite 波动聚集 + 肥尾跳跃 + 日内 U 型 + 涨跌停磁吸/封板/炸板 |
| P4 | 多冲击源叠加成 if-else 地狱 | 统一 `PriceShock` 队列：冲击只入队，引擎每跳统一消费（衰减曲线决定反应模式） |
| P5 | 快进与在线推进结果必须一致 | `WorldTickService.tickOne(userId)` 是唯一推进函数；调度器与 skip 快进共用；每账户一把 `ReentrantLock` 互斥 |
| P6 | 不可复现、重开无差异 | `world_seed`(long) 入库，全世界随机数走 `WorldRandom.of(seed, salt)`；测试断言同种子逐跳一致 |
| P7 | 事件线性模板重复感 | 组合式事件工厂：75 骨架 × 11 纪元 × 7 反应模式 × 槽位随机；弹幕 150+ 条 |
| P8 | 中期无目标 | 营业部 NPC 排行榜、资产段位、阶段挑战徽章、新手引导 |
| P9 | 内存态重启丢失 | 行情/冲击/剧本以内存为权威，重启由 `user_stock_state` + `user_game_state` Checkpoint 重建；封板按价位重判 |
| P10 | 并发交易超卖/资不抵债 | 交易 `SELECT ... FOR UPDATE`（user + position 顺序加锁）；行情 `version` 乐观锁；100 线程压测守恒 |
| P11 | emoji 风格不统一 | **全项目禁用 emoji**；功能图标 = SVG sprite（墨色描边/currentColor），复杂形象 = AI 生成 PNG，三态 = 文字徽章，涨跌 = CSS 色块 |
| P12 | 前端"AI 感" | 禁紫蓝渐变/玻璃拟态；米白纸底、2px 墨色描边、3px 硬阴影、站酷快乐体标题、红涨绿跌 |
| P13 | 每日历史数据缺口 | `user_daily_snapshot` 每日收盘落库（资产/指数/NPC 资产），供曲线、排行榜、终局 |
| P14 | T+1/停牌/ST/除权规则易漏 | 卖出可卖股数次日解冻；halted 价格冻结；ST 涨跌停 ±5%；除权在开盘首跳前处理 |
| P15 | 融资爆仓判定 | 担保比例 = (现金+市值)/负债；<140% 追保、<120% 强平（跌停封死顺延）、资不抵债 → BANKRUPT |

**通用工程约定**：TDD（先写失败测试 → 实现 → 通过 → 提交）；常量集中在 `game/constants`；金额用 `BigDecimal`（行情内部 double，落库与交易 BigDecimal，分位 HALF_UP）；游戏内时间一律"第几天 + 第几跳"，不用墙钟（仅午休计时用挂钟，且不进随机协议）。

## 2. 硬约束（冻结决策，改动前先确认）

1. **无 Redis / 无 MQ**；状态内存权威 + MySQL Checkpoint 落库。
2. **随机唯一入口**：引擎/结算路径禁止 `new Random()` / `Math.random()`，一律 `WorldRandom.of(seed, salt)`；同 seed 同结果，快进与正常推进等价。
3. **并发协议**：`QuoteBoardRegistry.worldLock(userId)` 覆盖调度/快进/交易，且"锁内开启事务、锁内提交"；`sys_user`/`user_position` 悲观锁 FOR UPDATE；`user_stock_state.version` 乐观锁。
4. **节奏常量**（`GameConstants`）：45+45 跳、`TICK_MS=1000`、`LUNCH_MS=10000`、`DAYS_PER_YEAR=24`、`TOTAL_DAYS=72`。
   纪元边界写在"36 交易日/年"基准日历里、由 `EraScheduler.scale()` 按当前年长等比映射，剧本撒点的三年区间同样推导——**想再调一局长度，只改 `DAYS_PER_YEAR`/`TICK_MS` 两个数**，前端镜像常量在 `frontend/src/constants/rhythm.js`（改后端记得同步）。
5. **交易规则**：红涨绿跌、T+1、涨停只可卖不可买、±10%（ST ±5%）、佣金万 3 最低 5 元、印花税千 1（仅卖出）、追保 140% / 强平 120%、年化 8%（日息 = 年化/36）。
6. **指数口径**：等权（`IndexCalculator`）；**开盘管线在所有价格变动完成后必须统一重算开盘指数**（跳空/复牌/剧本钉板全部计入，`e6fbc19` 修复，不得回退）。
7. **视觉**：贴纸风三件套（2px 墨描边 / 硬阴影 / 圆角）、标题用 `--font-fun`、全站禁 emoji、图标走 sprite + currentColor、色值只走 `tokens.css` 令牌（唯一例外 `styles/theme.js`）。
8. **吉祥物**：牛市 = 牛小牛（暖棕 + 大弯角），熊市 = 熊猫滚滚（黑白 + 黑眼圈）；**不靠红绿区分物种**；大图 PNG、小图 SVG symbol（`bear` 画的是熊猫，id 不改）。
9. **git 纪律**：`git add` 显式文件路径，**永不用 `-A`/`.`**；commit message 用 `type(scope): 中文说明`。
10. **跳过只对当日**：收盘后不给"跳过下一交易日"（见规格 §4.5）；服务端 skip 仍接受 NOT\_STARTED/CLOSED，专供测试与未来"一键快进 N 天"。
11. **验收习惯**：改后端先写失败测试并跑 `mvn test`；改前端跑 `npm run build` 并用浏览器走查关键页面，截图归档 `docs/design/shots/`。

## 3. 关键机制速查（最易踩坑处）

- **状态机**：`NOT_STARTED → MORNING_OPEN → LUNCH_BREAK → AFTERNOON_OPEN → SETTLING → CLOSED → BANKRUPT/FINISHED`；`MarketStateService.startDay` 是唯一日入口（隔夜跳空/纪元/复牌/剧本/打新时序都在里面）。
- **单跳引擎**：`WorldTickService.tickOne`（世界锁 + TransactionTemplate 锁内提交）；每跳以 `WorldRandom.of(seed,"tick{day}.{tick}")` 重播种，消费顺序固定（beginTick → 逐股 → 封板掷骰）。
- **结算 12 步**：`SettlementService.settle` —— prev_close 双写、T+1 解冻、融资计息、追保/强平/爆仓、日评、段位、快照、**日K落库（在重置 OHL 之前）**、OHL 重置、状态落位。
- **指数两个口径**：`user_game_state.prev_index` = 当日开盘基准（昨收，开盘写入、盘中只读）；`index_value` = 当前指数（每跳 checkpoint 覆盖）。熔断判定与弹幕触发读 `board.prevIndex`，盘中重启靠 prev_index 恢复基准（迁移 `V20260929_01`）。
- **收盘涨跌幅**：结算写 `user_stock_state.day_change_pct`；收盘态行情用当日日K bar 还原 OHLC 与昨收（`9e22f14`）。原因：结算把 prev_close 双写成收盘价，实时口径恒 0。
- **分时与日K**：`GET /api/stocks/{id}/quotes`、`GET /api/market/index` 返回**固定 90 点时间轴**（未到点为 null、午休断轴），前端按 `TICK_MS` 轮询；`GET /api/stocks/{id}/kline?limit=60` 读 `stock_daily_bar`（快进与正常推进同路径落库）。
- **事件系统**：`EventFactory.maybeRoll` 单 salt 掷骰（`"event{day}.{tick}"`），PendingAction 入队时预填文案，flush 阶段零掷骰；消息三态 PENDING → CONFIRMED/DENIED。
- **WS 四通道**：`/user/queue/{quotes,messages,notifications,danmu}`，握手 token 走 STOMP connectHeaders；**调度器只推进 WS 在线用户的世界**（刷新页面后必须补连，见 `user store ensureWsConnected`）。
- **前后端口径陷阱**：① WS 推送的 `changePct` 是小数比率（0.0998），前端 store 统一 ×100 后再用（封板阈值 9.8、`fmtPct` 都依赖这点）；② `MarketSnapshot` 只有 `{tickIndex,indexValue,quotes:[{stockId,current,changePct}]}`，行情表的封板/停牌/ST/连板来自 REST 而非 WS，不要凭空造列；③ 统一响应 `R{code,msg,data}`，`code=0` 成功，401 清 token 跳登录；④ NPC 头像由 `NpcAvatar` 用 `import.meta.glob` 按 code 小写取图，JUSHI/LAOQIAN/XINJIMIN/CHENGXUYUAN 无 PNG、走姓氏色块兜底属正常。
- **文案三级池（反重复）**：弹幕按"个股专属 → 板块 → 全局"混抽——`game/constants/StockPersonas.java`（24 股 × 绰号/股性/5 条专属弹幕/3 条专属事件文案）、`DanmuTexts.SECTOR_FLAVOR`（21 板块 × 4 条）、`DanmuTexts` 九个全局类别池。个股级触发先掷一个 double 决定层级（50/25/25），大盘级与横盘只走全局池。
- **事件文案按股定制**：`EventFactory` 在 STOCK 级事件命中目标股后，45% 用该股 `persona.news` 模板替换通用正文（仍走 `{stock}` 槽位渲染）。掷骰固定在该位置消费，快进与逐跳等价。
- **重要消息"当面"提醒**：前端 `GameLayout` 每 5 秒拉一次未读（`stores/messages.js#pollAndPresent`），按 `constants/messageMeta.js#channelOf` 分流——AUTHORITY 或标题含千股跌停/涨停/熔断/救市/崩盘/制裁/黑7月/强平/停牌核查 → **头条大弹幕**（`HeadlineDanmu.vue`，`pointer-events:none`，飘过即走）；SYSTEM/INSIDER 与传闻追报 → **右下角浮层**（`NoticePopup.vue`，6 秒自动收起，可点进消息中心）；其余只留在消息中心。首轮静默（`primed`），刷新页面不会把历史未读全弹一遍。
  **为什么用轮询而不是 WS**：`PushService.pushMessage` 全仓没有任何生产调用点（消息只落库不推送），补齐要动 10 个写入方的构造器；5 秒拉未读一处解决且覆盖所有来源。要改回 WS，就把 `MessageService.publish()` 接上推送并删掉 `pollAndPresent` 的定时器。
- **票名重起过 7 个**（鸱夷酒业→九坛老窖、片爷科技→芯火半导体、极乐传媒→造梦传媒、智算无穷→天算算力、阡陌商社→万家商社、百丈钢铁→炉火钢铁、通天信联→千里信联）：`StockDefs.java` 与 `frontend/src/constants/stocks.js` 必须同步，存量库的 `stock_info` 也要 UPDATE（新库由 WorldSeeder 重播）。
- **弹幕黑板（前端）**：`DanmuBar.vue` 5 条道 + 限速队列（240ms 一拍、道忙就等），按 `stores/messages.js` 的 `danmuVersion/danmuSeq` 取增量——**不要 watch 数组长度**，环形缓冲封顶到 30 条后长度不再变化，监听器会永久失效（就是历史上"不开关一次不飘、一开关糊一屏"的病根）。默认开启，重新开启时把游标推到最新，不回放过期积压。
- **个股历史消息**：`GET /api/messages?stockId=` 服务端过滤（`MessageService.list` 第 6 个参数），个股页拉 60 条；板块级/大盘级消息不带 `stockId`，因此不会出现在单只票的列表里（要把板块新闻也归进去，需给 `user_message` 加 `sector` 列，见 §6）。
- **前端菜单/路由**：`GameLayout.vue` 竖排菜单；`/market /stock/:id /assets /messages /leaderboard /summary /result`；涨跌与金额格式化统一在 `frontend/src/utils/format.js`。

## 4. 环境与运维

要求：JDK 17、Node.js、MySQL 8（开发库 `hold_year`、测试库 `hold_year_test`，账号见 `src/main/resources/application.properties`）。Shell 用 PowerShell 7（`pwsh`），禁用 Windows PowerShell 5.1。

| 操作 | 命令（仓库根） |
| --- | --- |
| 新库初始化 | 建库后对 `hold_year`、`hold_year_test` 各执行一次 `src/main/resources/db/schema.sql` |
| 旧库升级 | 按 [db-migrations.md](db-migrations.md) 手工执行 3 份 V2026xx 脚本（两个库都要） |
| 启动后端 | `.\mvnw.cmd spring-boot:run`（8080） |
| 启动前端 | `cd frontend; npm run dev`（5173，`/api`、`/ws` 代理到 8080） |
| 后端测试 | `.\mvnw.cmd test` |
| 前端构建 / 本地预览生产包 | `cd frontend; npm run build` / `npm run preview` |

**坑**：① 测试无 H2 兜底，`hold_year_test` 不存在会大面积报 `Unknown database`；② 旧库不执行迁移会让 `user_game_state` 读写报 `Unknown column`；③ 前端依赖后端，先起后端；④ `mvnw.cmd` 内部调用 Windows PowerShell 5.1，某些机器会死在 `(Get-Item ~/.m2).Target[0]`（报 `Cannot index into a null array` / `Cannot start maven from wrapper`）——可直接用 wrapper 已下载的 Maven：设好 `JAVA_HOME` 后执行 `& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\apache-maven-*\bin\mvn.cmd" test`；⑤ 本机 `localhost` 解析到 IPv6，用 `127.0.0.1` 探端口/代理可能看起来"拒绝连接"，浏览器与脚本请用 `localhost`。

**回归防护**：任何触及开盘管线 / 结算 / 引擎的改动，必跑 `VolatilityEngineTest`、`FastForwardServiceTest`、`TradeConcurrencyTest`、`WorldTickServiceTest`，并复验"快进与正常推进同种子结果一致"。

## 5. 验收状态

- **Phase 0-13 全部完成并通过验收**（后端引擎/调度/交易/融资/事件/NPC/剧本/打新/WS/REST → 前端 8 页 + 组件 → 终局验收）。
- 后端全量 **365 tests，0 Failures / 0 Errors**（人设 8 弹幕/6 事件 × 24 股、板块 6 条 × 21、跨池零重复、消息按 stockId 过滤、剧本日撒点不死循环）；一局 72 交易日约 2 小时；前端 `npm run build` 通过，入口主包 15.39 kB（vendor 按 echarts / element-plus / vue 拆块）。
- 验收图集：`docs/design/shots/01-login … 08-result`（另含 03b 封板、05b 已买入两张状态图）。
- 后续维护轮次（2026-09-29）：资产页三类流水接口与接入 → 结构整理与占位文案清理 → `prev_index` 根治 → 规格 §11 对齐 → 前端拆包。

## 6. 还值得做的事（都不是缺陷）

0. **把"当面提醒"改回 WS 推送**：见 §3 最后一条，目前靠前端 5 秒轮询兜住，实时性差 0~5 秒。
1. **倍速按钮（1x/2x/4x）**：`TICK_MS` 目前是编译期常量，要做运行期可切换需把它变成每账户的运行时参数，并让午休计时（`lunchStartedNanos` 用挂钟）与状态栏倒计时一起跟着档位走。随机序列按跳消费，倍速**不影响**同种子可复现性。

1. **把板块/大盘级消息也挂到个股页**：给 `user_message` 加 `sector` 列（含迁移），个股页按"stockId 或 所属板块"取消息，历史区才真正"全"。
2. **Element Plus 按需引入**：`main.js` 目前 `app.use(ElementPlus)` + 注册全部图标，而全站只用到 `el-dialog`、`el-input-number` 与 `ElMessage`/`ElMessageBox`，白担约 960 kB（min）。改按需引入需逐页核对弹窗与提示样式。
2. **生产化打包/部署说明**：当前只有开发模式运行手册，无部署文档。
3. **纪元叙事文案**：11 条在 `game/model/enums/EraCode.java` 的 `narrative` 字段，属产品文案，可随时改（测试只断言"通知正文等于该字段"）。
