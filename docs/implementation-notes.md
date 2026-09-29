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
4. **一局一档案，节奏只有 `GameMode` 一个真源**：跳数固定 45+45=90（波动标定按跳消费，改墙钟不动行情）；年长、跳毫秒、午休、涨跌停幅度、最短纪元长度都在 `game/model/enums/GameMode.java`（标准 24 天/年·1000ms·10s·±10%·纪元≥3 天；快速 12 天/年·500ms·5s·±20%·纪元≥2 天）。
   纪元边界写在"36 交易日/年"基准日历里、由 `EraScheduler.scale()` 按本局年长等比映射，剧本撒点的区间同样推导——**加一档节奏 = GameMode 加一行**，不要往 `GameConstants` 里再加"当前节奏"常量（`TICK_MS/LUNCH_MS/DAYS_PER_YEAR/TOTAL_DAYS` 已于 2026-09-29 删除，它们和每局档案并存必然漂移）。
   前端也不镜像节奏：倒计时与轮询读 `/api/market/state` 的 `tickMs/daySeconds/totalDays`，模式选择器读 `/api/game/modes`（原 `constants/rhythm.js` 已删）。
5. **交易规则**：红涨绿跌、T+1、涨停只可卖不可买、普通股 ±10%（ST ±5%）——**快速模式档案下为 ±20%（ST ±10%）**，涨跌停幅度由 `Quote.limitPct` 承载（行情板恢复时按本局 `GameMode` 下发），ST 恒取本局幅度的一半、所以戴帽摘帽不用回改；佣金万 3 最低 5 元、印花税千 1（仅卖出）、追保 140% / 强平 120%、年化 8%（日息 = 年化/36）。
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
- **分时与日K**：`GET /api/stocks/{id}/quotes`、`GET /api/market/index` 返回**固定 90 点时间轴**（未到点为 null、午休断轴），前端按本局 `tickMs` 轮询；`GET /api/stocks/{id}/kline?limit=60` 读 `stock_daily_bar`（快进与正常推进同路径落库）。
- **事件系统**：`EventFactory.maybeRoll` 单 salt 掷骰（`"event{day}.{tick}"`），PendingAction 入队时预填文案，flush 阶段零掷骰；消息三态 PENDING → CONFIRMED/DENIED。
- **WS 四通道**：`/user/queue/{quotes,messages,notifications,danmu}`，握手 token 走 STOMP connectHeaders；**调度器只推进 WS 在线用户的世界**（刷新页面后必须补连，见 `user store ensureWsConnected`）。
- **前后端口径陷阱**：① WS 推送的 `changePct` 是小数比率（0.0998），前端 store 统一 ×100 后再用（封板阈值 9.8、`fmtPct` 都依赖这点）；② `MarketSnapshot` 只有 `{tickIndex,indexValue,quotes:[{stockId,current,changePct}]}`，行情表的封板/停牌/ST/连板来自 REST 而非 WS，不要凭空造列；③ 统一响应 `R{code,msg,data}`，`code=0` 成功，401 清 token 跳登录；④ NPC 头像由 `NpcAvatar` 用 `import.meta.glob` 按 code 小写取图，JUSHI/LAOQIAN/XINJIMIN/CHENGXUYUAN 无 PNG、走姓氏色块兜底属正常。
- **文案三级池（反重复）**：弹幕按"个股专属 → 板块 → 全局"混抽——`stock-personas.txt`（24 股 × 绰号/股性/8 条专属弹幕/6 条专属事件文案）、`danmu.txt` 的 21 板块池（×6）与 9 个全局类别池。个股级触发先掷一个 double 决定层级（50/25/25），大盘级与横盘只走全局池。
- **事件文案按股定制**：`EventFactory` 在 STOCK 级事件命中目标股后，45% 用该股 `persona.news` 模板替换通用正文（仍走 `{stock}` 槽位渲染）。掷骰固定在该位置消费，快进与逐跳等价。
- **重要消息"当面"提醒（全实时，无轮询）**：后端每条消息落库的同一条路径都会 WS 推一份（见下条），前端 `stores/messages.js#pushMessage` 收到后按 `constants/messageMeta.js#channelOf` 分流——AUTHORITY 或标题含千股跌停/涨停/熔断/救市/崩盘/制裁/黑7月/强平/停牌核查 → **头条大弹幕**（`HeadlineDanmu.vue`，`pointer-events:none`，飘过即走）；SYSTEM/INSIDER 与传闻追报 → **右下角浮层**（`NoticePopup.vue`，6 秒自动收起，可点进消息中心）；其余只留在消息中心。首轮静默（`primed`），刷新页面不会把历史未读全弹一遍。
  **消息写入与推送的唯一出口**：`MessageService.publish(UserMessage)` —— insert 完立刻把同一份 `MessageDTO` 推给 `/user/queue/messages`（形状与 REST 列表一致，前端一套逻辑吃两边）。全仓 4 个写入方（`NotifyPublisher`/`EventFactory`×2/`MainScriptService`）一律走它，**不要**再直接 `userMessageMapper.insert`——绕过它就等于写了条玩家永远看不到的哑消息。
  前端只在进主布局时补拉一次未读（`syncUnread`，只登记不播报）用来兜住"连接建立之前"落库的历史与铃铛角标；调度器本就只推进 WS 在线用户的世界，掉线期间不产生消息，所以不需要轮询。实测：盘中 20 秒 + 快进结算，`/api/messages` 请求数 **0**，快进到浮层出现 **453ms**（旧轮询最多 5 秒）。
- **票名重起过 7 个**（鸱夷酒业→九坛老窖、片爷科技→芯火半导体、极乐传媒→造梦传媒、智算无穷→天算算力、阡陌商社→万家商社、百丈钢铁→炉火钢铁、通天信联→千里信联）：股票档案只有后端一份 `StockDefs.java`（前端那份手抄表 `constants/stocks.js` 已删，板块名曾长期漂移），页面显示的名称/板块/发行价全部来自 `GET /api/stocks`；改名后存量库的 `stock_info` 也要 UPDATE（新库由 WorldSeeder 重播）。
- **弹幕黑板（前端）**：`DanmuBar.vue` 5 条道 + 限速队列（240ms 一拍、道忙就等），按 `stores/messages.js` 的 `danmuVersion/danmuSeq` 取增量——**不要 watch 数组长度**，环形缓冲封顶到 30 条后长度不再变化，监听器会永久失效（就是历史上"不开关一次不飘、一开关糊一屏"的病根）。默认开启，重新开启时把游标推到最新，不回放过期积压。
- **倒计时口径**：`MarketStateDTO.countdownTicks` 是**本会话剩余跳数**（早盘 `45 - tick_index`、午盘 `90 - tick_index`、非交易态 0、越界夹到 0），不是全天剩余跳数——早盘拿 90 做基准会让早盘倒计时正好是午盘的两倍。前端 `StatusBar` 用"截止时间戳"而不是本地每秒 -1：快速模式每跳 0.5 秒，倒计时是小数，`-1` 会跨过 0 变成 `-01:-0.5` 那种负数。
- **重开一局清表**：`GameEndService.doRestart` 必须连 `stock_daily_bar` 一起清——它是日K数据源，漏清会让新的一局开局就带着上一局的蜡烛图。以后加"按用户"维度的新表，记得同步进这份清单。
- **个股历史消息**：`MessageService.list` 的第 6/7 个参数是 `stockId` + `includeSector`。个股页用 `stockId&includeSector=true`，一次拿回"本票专属（stock_id 命中）+ 本票所属板块（sector 命中且 stock_id 为空）"；板块名由后端按 `StockDefs` 反查，前端不需要知道。板块级消息的 `sector` 由 `EventFactory` 在 SECTOR 级事件落库时写入（迁移 `V20260929_02`）。历史存量消息的 sector 为 NULL，只有升级后新产生的板块消息能被个股页捞到。
- **文案库五份文件**（`src/main/resources/content/`，见 §7）：`stock-personas.txt`（24 股 × 8 弹幕 + 6 事件）、`sector-flavor.txt`（21 板块 + 大盘 × 权威/媒体/传闻含追报/玩梗各 2）、`npc-lines.txt`（4 场景 × 5 + 8 NPC × 5 闲聊）、`danmu.txt`（9 全局池 + 21 板块池）、`event-copy.txt`（75 条事件骨架的标题/正文变体与追报）。前四份被 `StockPersonasTest` 的"全局零重复"总校验覆盖（≥700 条唯一），任何一处抄重直接红。
- **三条"排期尾巴"（2026-09-30 修）**：① 纪元 3 长度下限 4 天（`EraScheduler.CRASH_CHAIN_MIN_ERA_LEN`）——`ScriptDayScheduler` 要排"≥2 天千股跌停 → 救市 → 次日千股涨停"，快速模式年长 12 天、抖动会把纪元压到 2 天，股灾就演成一日游、涨停还会漏到下一个纪元的第一格；② 救市日最多排在纪元 3 倒数第二天，保证"救市次日"的涨停留在本纪元内；③ 申购日 ≤ 收官日 - 4（`IPO_LIFECYCLE_TAIL`），给 D+2/D+4 留路。三条都由 `EraSchedulerTest`/`ScriptDaySchedulerTest` 用 200~1000 个种子扫过。
- **一局一档案（GameMode）**：标准/快速两套节奏在 `game/model/enums/GameMode.java` 里各自带值（年长、跳毫秒、午休、涨跌停、最短纪元长度），建局时选定写进 `user_game_state.game_mode`，**一局一选、本局进行中不可改**：注册不绑模式，开局前（大盘页状态条的模式选择器）与终局后（"再来一局"旁的选择器）都走 `POST /api/game/restart {mode}`；restart 允许"终局后"与"从未开过局"两种时机，其余一律拒绝。**不要**再用 `GameConstants.TICK_MS/DAYS_PER_YEAR/TOTAL_DAYS` 做运行时判定——那些只是标准模式的默认值，运行时一律 `GameMode.of(gs.getGameMode())`。调度器改成 500ms 心跳（`SCHEDULER_HEARTBEAT_MS`）+ 每账户按本局 `tickMs` 门控（`QuoteBoard.lastTickAtNanos`）；快进与测试直接调 `tickOne`，不看墙钟，所以"快进=逐跳"等价与同种子可复现都不受影响。前端从 `/api/market/state` 读 `mode/tickMs/daySeconds/totalDays/limitPct`，模式清单（代码/名称/一句话说明/天数）由 `GET /api/game/modes` 下发给 `ModePicker`，注册不绑模式。
- **提醒消息只有一个出口**：任何"要告诉玩家一句话"的通知（纪元切换、结算播报、除权、追保/强平、打新、剧本收官、称号解锁、NPC 台词）一律走 `event/NotifyPublisher`（`system(...)` / `npc(...)`，字段惯例 type=NOTIFY、未读、当日、created_at 现取）。别再在各服务里 `new UserMessage()` 抄一份——历史上抄了 7 份，字段还各有出入。行情事件（EVENT/三态传闻）仍走 `EventFactory`/`MainScriptService`，它们要写 sector/truth_status/followup_of，语义不同。
- **心跳与冷用户节流**：`MarketScheduler` 每 `SCHEDULER_HEARTBEAT_MS`(100ms) 扫一遍在线用户，但**先做内存预判**（`dueForBeat`）：有行情板的按本局 `tickMs` 判到点；没有板的（未开过局 / 刚重启 / 刚登出）按 1 秒节流放行一次。别去掉这个节流——每个停在"还没开盘"页面的在线账号都会变成每秒 10 次 `selectOne`。
- **前端菜单/路由**：`GameLayout.vue` 竖排菜单；`/market /stock/:id /assets /messages /leaderboard /summary /result`；涨跌与金额格式化统一在 `frontend/src/utils/format.js`。

## 4. 环境与运维

要求：JDK 17、Node.js、MySQL 8（开发库 `hold_year`、测试库 `hold_year_test`，账号见 `src/main/resources/application.properties`）。Shell 用 PowerShell 7（`pwsh`），禁用 Windows PowerShell 5.1。

| 操作 | 命令（仓库根） |
| --- | --- |
| 新库初始化 | 建库后对 `hold_year`、`hold_year_test` 各执行一次 `src/main/resources/db/schema.sql` |
| 旧库升级 | 按 [db-migrations.md](db-migrations.md) 手工执行 5 份 V2026xx 脚本（两个库都要） |
| 启动后端 | `.\mvnw.cmd spring-boot:run`（8080） |
| 启动前端 | `cd frontend; npm run dev`（5173，`/api`、`/ws` 代理到 8080） |
| 后端测试 | `.\mvnw.cmd test` |
| 前端构建 / 本地预览生产包 | `cd frontend; npm run build` / `npm run preview` |

**坑**：① 测试无 H2 兜底，`hold_year_test` 不存在会大面积报 `Unknown database`；② 旧库不执行迁移会让 `user_game_state` 读写报 `Unknown column`；③ 前端依赖后端，先起后端；④ `mvnw.cmd` 内部调用 Windows PowerShell 5.1，某些机器会死在 `(Get-Item ~/.m2).Target[0]`（报 `Cannot index into a null array` / `Cannot start maven from wrapper`）——可直接用 wrapper 已下载的 Maven：设好 `JAVA_HOME` 后执行 `& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\apache-maven-*\bin\mvn.cmd" test`；⑤ 本机 `localhost` 解析到 IPv6，用 `127.0.0.1` 探端口/代理可能看起来"拒绝连接"，浏览器与脚本请用 `localhost`。

**回归防护**：任何触及开盘管线 / 结算 / 引擎的改动，必跑 `VolatilityEngineTest`、`FastForwardServiceTest`、`TradeConcurrencyTest`、`WorldTickServiceTest`，并复验"快进与正常推进同种子结果一致"。

## 5. 验收状态

- **Phase 0-13 全部完成并通过验收**（后端引擎/调度/交易/融资/事件/NPC/剧本/打新/WS/REST → 前端 8 页 + 组件 → 终局验收）。
- 后端全量 **396 tests，0 Failures / 0 Errors**（同种子可复现、快进=逐跳、人设与板块/NPC 文案零重复总校验、事件骨架 75 条与 event-copy.txt 一一对应、文案文件全部登记、剧本日撒点不死循环、prev_index 基准、三类流水查询、模式档案与 /api/game/modes、提醒消息落库惯例（NotifyPublisher）、心跳冷用户节流）；标准局 72 交易日约 2 小时、快速局 36 天约 30 分钟；前端 `npm run build` 通过，入口主包 17.6 kB（vendor 按 echarts / element-plus / vue 拆块）。
- 验收图集 `docs/design/shots/`：01-login / 02-market / 03-stock / 03b-limit-sealed / 04-assets / 05-messages / 05-stock-bought / 06-rank / 07-summary / 08-result / 09-notice-popup（右下角浮层）/ 10-danmu-board（分道弹幕）/ 11-quick-mode-picker（模式选择）/ 12-quick-market（快速模式实盘：±20% 行情）/ 13-stock-detail（个股页：涨跌停虚线在 ±20% 处）。
- 后续维护轮次（2026-09-29）：资产页三类流水接口与接入 → 结构整理与占位文案清理 → `prev_index` 根治 → 规格 §11 对齐 → 前端拆包 → 节奏压缩与弹幕/消息体验重做 → 一局一档案 GameMode → 倒计时三连修 → 涨跌停价由后端按本局下发 → **全部游戏文案外置到 `resources/content/`（含 75 条事件骨架的标题正文）、删掉前端手抄的股票表与节奏镜像、清掉死常量/死配置/未用图标/未用 import 与死导出**
- 同日第二轮（结构整治）：`game/content` 收拢全部文案读取层 → `EventSkeletons` 499→227 行 → 7 份重复的"提醒消息落库"样板收进 `event/NotifyPublisher`（纪元切换标题不再把 `BULL_START` 这种枚举名漏给玩家）→ 心跳对"还没缓存行情板"的在线用户按 1 秒节流（原来每个未开盘账号每秒 10 次空查库）→ 换行符统一 LF。

## 6. 还值得做的事（都不是缺陷）

- **倍速按钮（1x/2x/4x）**：跳毫秒已经是每局档案里的值（`GameMode.getTickMs()`），再做倍速只需把它降级成"每账户运行时参数 + 档案默认值"，并让午休计时（`lunchStartedNanos` 用挂钟）与状态栏倒计时随动。随机序列按跳消费，倍速**不影响**同种子可复现性。
- **Element Plus 按需引入（已完成 2026-09-30）**：全站只用 `el-dialog` + `el-input-number` + `ElMessage`/`ElMessageBox`，改成"用到哪个 import 哪个"。**必须走 `element-plus/es/components/<x>/index.mjs` 深层路径**——从桶口 `'element-plus'` 取仍然会把全量组件拖进来（实测只降到 808 kB）。样式在各自组件里引 `.../style/css`，命令式的两个（message / message-box）在 `main.js` 里统一引。`vendor-element` 960 kB → **86.65 kB**（gzip 294 → 29.8），杂项 `vendor` 205.85 → 101.04 kB。以后新增 el-* 组件时记得同处补 `style/css`，否则功能正常但没样式。
- **生产化打包/部署说明**：当前只有开发模式运行手册，无部署文档。
- **纪元叙事文案**：11 条在 `game/model/enums/EraCode.java` 的 `narrative` 字段，是唯一还留在 Java 里的产品文案（测试断言"通知正文等于该字段"，改起来也安全）。

## 7. 文案维护（改文案不用改代码）

全部游戏文案在 `src/main/resources/content/` 五个纯文本文件里，**中文一行一条**，改完重启后端即生效；格式故意不用 JSON/YAML，避免引号与转义把文案搞成代码。

| 文件 | 管什么 | 分组写法 |
| --- | --- | --- |
| `danmu.txt` | 股吧弹幕：9 个全局类别池（涨停/跌停/急拉/跳水/大跌/大涨/横盘…）+ 21 个板块池 | `@LIMIT_UP`、`@SECTOR:白酒` |
| `stock-personas.txt` | 24 只票的绰号、股性、专属弹幕（8 条）、专属事件文案（6 条） | `@5:DANMU`（数字 = 股票 id） |
| `sector-flavor.txt` | 21 板块 + 大盘的权威公告/媒体报道/股吧传闻/玩梗 | `@白酒:RUMOR`，条目 `标题\|正文[\|追报]` |
| `npc-lines.txt` | 4 个角色线场景台词 + 8 位 NPC 的性格闲聊 | `@SCENE:DAMA_RECOMMEND`、`@CHATTER:DAMA` |
| `event-copy.txt` | 75 条事件骨架的标题/正文变体与证实·辟谣追报 | `@fund-big-order`，条目 `标题\|正文` / `sector:白酒\|标题\|正文` / `>追报` |

规则：`#` 开头是注释、空行忽略、`@分组名` 开一个分组、其余每行一条。**分组缺失或为空会在启动时直接抛错**（宁可起不来，也不要运行时静默没文案）。

槽位：`{stock}` 换成股票名（仅个股类可用）、`{amount}` 换成金额、`{user}` 换成 NPC 或玩家名。禁 emoji（有测试拦）。

改完必跑 `mvn test`，这些测试就是文案的 lint：

- `ContentCatalogTest`：目录里每个 .txt 都必须已在 `ContentCatalog.loadAll()` 登记（新增文件忘了登记会红）。
- `StockPersonasTest`：股数/板块覆盖齐、跨全部文案池**零重复**（重复凑数会红）、无 emoji、无"占位/TODO"。
- `EventSkeletonsTest`：骨架与 `event-copy.txt` 分组**一一对应**、每条骨架 ≥2 变体、槽位白名单、追报与来源匹配。

注意：**增删文案条目会改变同种子下的抽取结果**（随机数按序消费）。引擎行为、可复现性与"快进=逐跳"等价都不受影响，但老存档用同一个种子 replay 出的事件/弹幕序列会和以前不同——这是改文案的预期代价，不必为它做兼容分支。
