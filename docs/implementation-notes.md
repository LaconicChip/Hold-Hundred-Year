# 实现备忘（冻结决策 · 机制速查 · 运维 · 验收）

> 本文件是**实现层的唯一备忘**：设计意图看 [spec.md](spec.md)，怎么落地、哪些不能动、坑在哪看这里。
> 规则冲突时优先级：**设计规格 > 本备忘 > 其他**。

## 1. 设计阶段确认的痛点与结论（P1-P15，实现时不可回退）

| # | 痛点 | 结论/约束 |
| --- | --- | --- |
| P1 | 节奏太慢 | 1 天 = 90 跳（上午 45 → 午休 → 下午 45）；跳数与波动标定不变，墙钟由"3 秒/跳 × 108 天 ≈ 9 小时"压到"1 秒/跳 × 72 天 ≈ 2 小时"（2026-09-29） |
| P2 | 纯随机无叙事、纯剧本太公式 | 11 纪元顺序与全覆盖硬保证，边界 ±1~3 天抖动、强度区间随机，注册时掷点入库 |
| P3 | 随机游走不像股市 | 三层因子（市场/板块/个股 β）+ GARCH-lite 波动聚集 + 肥尾跳跃 + 日内 U 型 + 涨跌停磁吸/封板/炸板 |
| P4 | 多冲击源叠加成 if-else 地狱 | 统一 `PriceShock` 队列：冲击只入队，引擎每跳统一消费（衰减曲线决定反应模式） |
| P5 | 快进与在线推进结果必须一致 | `WorldTickService.tickOne(userId)` 是唯一推进函数；调度器与 skip 快进共用；每账户一把 `ReentrantLock` 互斥 |
| P6 | 不可复现、重开无差异 | `world_seed`(long) 入库，全世界随机数走 `WorldRandom.of(seed, salt)`；测试断言同种子逐跳一致 |
| P7 | 事件线性模板重复感 | 组合式事件工厂：92 骨架 × 11 纪元 × 7 反应模式 × 槽位随机；弹幕 150+ 条 |
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
5. **交易规则**：红涨绿跌、T+1、涨停只可卖不可买、普通股 ±10%（ST ±5%）——**快速模式档案下为 ±20%（ST ±10%）**，涨跌停幅度由 `Quote.limitPct` 承载（行情板恢复时按本局 `GameMode` 下发），ST 恒取本局幅度的一半、所以戴帽摘帽不用回改；佣金万 3 最低 5 元、印花税千 1（仅卖出）、追保 140% / 强平 120%、年化 8%（日息 = 年化 ÷ 本局年交易日数，标准 24、快速 12）。**高利贷另算**（spec §6.6）：额度净资产 5 倍（借满 6 倍仓位）、日息按账龄递增、到期强卖，只进净资产与资不抵债判定、不进维持担保比例。
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
- **指数两个口径**：`user_game_state.prev_index` = 当日开盘基准（昨收，开盘写入、盘中只读）；`index_value` = 当前指数（每跳 checkpoint 覆盖）。熔断判定与弹幕触发读 `board.prevIndex`，盘中重启靠 prev_index 恢复基准。
- **收盘涨跌幅**：结算写 `user_stock_state.day_change_pct`；收盘态行情用当日日K bar 还原 OHLC 与昨收（`9e22f14`）。原因：结算把 prev_close 双写成收盘价，实时口径恒 0。
- **分时与日K**：`GET /api/stocks/{id}/quotes`、`GET /api/market/index` 返回**固定 90 点时间轴**（未到点为 null、午休断轴），前端按本局 `tickMs` 轮询；`GET /api/stocks/{id}/kline?limit=60` 读 `stock_daily_bar`（快进与正常推进同路径落库）。
- **分时图的纵轴不能把涨跌停价算进取值范围，但涨跌停价本身是硬边界**（2026-10-19 改前半段、2026-10-20 改后半段）：`StockChart.vue` 之前用 `dev = max(真实振幅, |涨停-昨收|, |昨收-跌停|)`，等于纵轴永远按 ±10%（快速 ±20%）铺满，盘中 1% 的波动在图上就是一条直线——玩家反馈"开局动一下、中间几乎平盘、收盘再动一下"，一半是引擎 U 型，一半是这把尺子。现在 `dev` 只看真实价格与均价，最小 ±0.8% 兜底、再留 15% 白；**但顶到涨停时这 15% 白会把 10% 的板顶到 11.5%，板贴不到顶**（玩家反馈"涨停时上面多显示一点点空白，很难看"），所以纵轴再被涨跌停价夹一道：上界不超过 `limitUp×1.001`、下界不低于 `limitDown×0.999`（留 0.1% 给涨跌停线的标签）。**右轴的百分比必须跟着夹紧后的左轴走**（`pctMin/pctMax` 从 `yMin/yMax` 算），否则两根轴对不上。
- **日K图内的股吧弹幕**（2026-10-19）：`KlineChart.vue` 接收 `danmu`（本票 + 大盘闲聊），按 `seq` 只消费新增，首帧最多回放最近 6 条；最多 4 条同道、1.2 秒一条、约 9~12 秒飘过，图层 `pointer-events:none` 且只盖绘图区（left 56 / right 46 / top 20 / height 58%），不挡缩放、拖动和 tooltip。完整列表仍是「股吧」标签页——弹幕是提醒"这里有人聊"，不是阅读器。
- **事件系统**：`EventFactory.maybeRoll` 单 salt 掷骰（`"event{day}.{tick}"`），PendingAction 入队时预填文案，flush 阶段零掷骰；消息三态 PENDING → CONFIRMED/DENIED。
- **WS 四通道**：`/user/queue/{quotes,messages,notifications,danmu}`，握手 token 走 STOMP connectHeaders；**调度器只推进 WS 在线用户的世界**（刷新页面后必须补连，见 `user store ensureWsConnected`）。
- **前后端口径陷阱**：① WS 推送的 `changePct` 是小数比率（0.0998），前端 store 统一 ×100 后再用（封板阈值 9.8、`fmtPct` 都依赖这点）；② `MarketSnapshot` 只有 `{tickIndex,indexValue,quotes:[{stockId,current,changePct}]}`，行情表的封板/停牌/ST/连板来自 REST 而非 WS，不要凭空造列；③ 统一响应 `R{code,msg,data}`，`code=0` 成功，401 清 token 跳登录；**一次进页会并发几个请求同时撞 401，提示必须带 `grouping: true`**，否则屏幕会叠六七层同样的"登录已过期"（便携版换档/删 `data\\` 后最常见）；④ NPC 头像由 `NpcAvatar` 用 `import.meta.glob` 按 code 小写取图，JUSHI/LAOQIAN/XINJIMIN/CHENGXUYUAN 无 PNG、走姓氏色块兜底属正常。
- **文案三级池（反重复）**：弹幕按"个股专属 → 板块 → 全局"混抽——`stock-personas.txt`（24 股 × 绰号/股性/8 条专属弹幕/6 条专属事件文案）、`danmu.txt` 的 21 板块池（×12）与 9 个全局类别池。个股级触发先掷一个 double 决定层级（50/25/25），大盘级与横盘只走全局池。
- **事件文案按股定制**：`EventFactory` 在 STOCK 级事件命中目标股后，45% 用该股 `persona.news` 模板替换通用正文（仍走 `{stock}` 槽位渲染）。掷骰固定在该位置消费，快进与逐跳等价。
- **重要消息"当面"提醒（全实时，无轮询）**：后端每条消息落库的同一条路径都会 WS 推一份（见下条），前端 `stores/messages.js#pushMessage` 收到后按 `constants/messageMeta.js#channelOf` 分流——AUTHORITY 或标题含千股跌停/涨停/熔断/救市/崩盘/制裁/黑7月/强平/停牌核查 → **头条大弹幕**（`HeadlineDanmu.vue`，`pointer-events:none`，飘过即走）；SYSTEM/INSIDER 与传闻追报 → **右下角浮层**（`NoticePopup.vue`，6 秒自动收起，可点进消息中心）；其余只留在消息中心。首轮静默（`primed`），刷新页面不会把历史未读全弹一遍。
  **消息写入与推送的唯一出口**：`MessageService.publish(UserMessage)` —— insert 完立刻把同一份 `MessageDTO` 推给 `/user/queue/messages`（形状与 REST 列表一致，前端一套逻辑吃两边）。全仓 4 个写入方（`NotifyPublisher`/`EventFactory`×2/`MainScriptService`）一律走它，**不要**再直接 `userMessageMapper.insert`——绕过它就等于写了条玩家永远看不到的哑消息。
  前端只在进主布局时补拉一次未读（`syncUnread`，只登记不播报）用来兜住"连接建立之前"落库的历史与铃铛角标；调度器本就只推进 WS 在线用户的世界，掉线期间不产生消息，所以不需要轮询。实测：盘中 20 秒 + 快进结算，`/api/messages` 请求数 **0**，快进到浮层出现 **453ms**（旧轮询最多 5 秒）。
- **票名重起过 8 轮**（最后一轮把 24 只全部换成"影射本板块龙头/梗股"的化名：九坛老窖→茅王酒业、芯火半导体→寒武芯能、卧龙新能→宁王新能、后羿光能→光茅绿能、回春堂→恒瑞药堂、大通银号→葵花银行、牛途证券→东财证券、汪洋水产→獐岛水产、天算算力→曙光算力、千里信联→兴芯通讯 等；再往前是鸱夷酒业→九坛老窖、片爷科技→芯火半导体、极乐传媒→造梦传媒、智算无穷→天算算力、阡陌商社→万家商社、百丈钢铁→炉火钢铁、通天信联→千里信联）：股票档案只有后端一份 `StockDefs.java`（前端那份手抄表 `constants/stocks.js` 已删，板块名曾长期漂移），页面显示的名称/板块/发行价全部来自 `GET /api/stocks`；改名后存量库的 `stock_info` 也要 UPDATE（新库由 WorldSeeder 重播）。
- **股吧弹幕（2026-10-16 改版，2026-10-19 移入标签页，2026-10-20 大改）**：玩家原话是"弹幕太少了太少了，现实中每只票尤其是热点票应该每时每刻都会有好多条弹幕"，而且"依旧无法正常显示在分时k线图中"。两个问题各有一根因，都已改：
  - **产出太少的根因是"节流按账户算 + 一次只有一条"**：原来任意两条弹幕至少隔 3 跳、同一类别隔 10 跳、且每跳最多产出一条——全市场一天只有三十来条，24 只票一分每票 1~2 条。现在改成**每只票各自成流**：每跳按热度给每只票掷一次骰子（冷门票 16% 开口、涨得越多话越多、封板再加一档，上限 90%），类别按**该票自己的状态**选（封板→涨停/跌停、±5%→急拉/跳水、±3%→次级、其余→**闲聊**；票在动的时候也有 35% 的概率说闲话），节流也改成**按票计**（同票同类隔 2 跳、同票任意两条隔 1 跳）。**实测（3 个交易日）：每票每天约 25 条、热门票 30~45 条，闲聊占一半以上**（改前每票每天 1~2 条）。
  - **"不显示"的根因是分时图根本没有弹幕层**：以前只有日K图有（内联在 `KlineChart.vue`），分时图没有。现在飘屏逻辑抽成共享组件 `DanmuLayer.vue`，**分时图与日K图都用它**（航道 5、间隔 0.7 秒、飞行 11 秒），`StockDetail.vue` 把 `forumPosts` 同时传给两个图。
  - **内容体量**：新增 `@CHATTER` 日常闲聊池（250+ 条，"这票明天怎么看/主力在干嘛/我是不是该割了"这类不与涨跌挂钩的碎碎念），每只票的专属弹幕从 8 条扩到 16 条，闲聊时按 50% 个股专属 / 25% 板块 / 25% 全局三级混抽。
  - **焦点票别再固定取"第一个越线的票"**（那样弹幕永远落在同几只票上），改为在同类候选里随机取；封板类别优先级提到大盘之前（指数 ±2% 时不该让"指数高歌猛进"占满每只票的股吧）。
  - 后端按账户留每股历史（每股 80 条 + 大盘闲聊 20 条，内存态不落库），个股页进页时 `GET /api/stocks/{id}/danmu` 一次拉回来，前端 `messages.setDanmuHistory` 存下、`forumPosts` 把历史与实时两路按后端下发的 `seq` 去重合并。**后端序号是唯一真源**，别在前端另起一套（否则实时那条与历史那条对不上、会出现重复条目）。
- **LED 快讯只报决策信息（2026-10-19 扩容）**：`TickerTape.vue` 现在按"先风险、后盘面"排序——持仓跌停封死/停牌出不去、担保比例逼近追保线、高利贷待还排最前；随后是市场宽度（涨/跌/平）、涨停/跌停与封死/停牌家数（涨跌停按每只票下发的 `limitUp/limitDown` 判，不能写死 9.8%，否则快速模式和 ST 全错）、指数与纪元、当前时段与剩余时间、领涨/领跌、最强/最弱板块、连板高度、持仓浮盈/仓位、持仓异动、今日持仓消息。**不要**把消息中心的通知再抄一遍；快讯只留"看了能改变操作"的。悬停会暂停滚动。
- **倒计时口径**：`MarketStateDTO.countdownTicks` 是**本会话剩余跳数**（早盘 `45 - tick_index`、午盘 `90 - tick_index`、非交易态 0、越界夹到 0），不是全天剩余跳数——早盘拿 90 做基准会让早盘倒计时正好是午盘的两倍。前端 `StatusBar` 用"截止时间戳"而不是本地每秒 -1：快速模式每跳 0.5 秒，倒计时是小数，`-1` 会跨过 0 变成 `-01:-0.5` 那种负数。
- **重开一局清表**：`GameEndService.doRestart` 必须连 `stock_daily_bar` 一起清——它是日K数据源，漏清会让新的一局开局就带着上一局的蜡烛图。以后加"按用户"维度的新表，记得同步进这份清单。
- **个股历史消息**：`MessageService.list` 的第 6/7 个参数是 `stockId` + `includeSector`。个股页用 `stockId&includeSector=true`，一次拿回"本票专属（stock_id 命中）+ 本票所属板块（sector 命中且 stock_id 为空）"；板块名由后端按 `StockDefs` 反查，前端不需要知道。板块级消息的 `sector` 由 `EventFactory` 在 SECTOR 级事件落库时写入。
- **文案库七份文件**（`src/main/resources/content/`，见 §7）：`stock-personas.txt`（24 股 × 8 弹幕 + 6 事件）、`sector-flavor.txt`（21 板块 + 大盘 × 权威/媒体/传闻含追报/玩梗各 2）、`npc-lines.txt`（4 场景 × 5 + 8 NPC × 5 闲聊）、`danmu.txt`（9 全局池 + 21 板块池，2026-10-16 每个板块池从 6 条扩到 12 条——6 条正是"股吧翻来覆去那几句"的根源）、`event-copy.txt`（160 条事件骨架的标题/正文变体与追报）、`era-narratives.txt`（11 个纪元叙事，每组恰好一行）、`shark-lines.txt`（高利贷 7 个场景台词）。除 `event-copy.txt`（由 `EventSkeletonsTest` 一一对应把关）外的六份被 `StockPersonasTest` 的"全局零重复"总校验覆盖（≥700 条唯一），任何一处抄重直接红。
- **高利贷的两本债（2026-10-10）**：`sys_user.debt` 是融资债、`sys_user.shark_debt` 是高利贷未清余额，后者恒等于台账 `user_shark_loan` 各笔之和（`SharkService.syncBalance` 每次重算，不做手工加减）。**担保比例只读前者，净资产与资不抵债读两者之和**——所以 `MarginService.marginCheck` 的入口短路条件是"两种债都没有"，只看融资债为 0 就短路会让欠高利贷的人永远死不了（测试 `insolventThroughSharkAloneStillEndsOnTheRooftop` 钉住这一点）。融资可借额度公式收在 `MarginService.marginBorrowableOf` 一处：`现金 + 市值 −（融资债 + 高利贷）× (1 + BORROW_LIMIT_RATIO)`，借款校验与 `/api/me/assets.borrowable` 共用；高利贷可借额在 `SharkService.borrowableOf`。到期强卖不复用 `MarginService.liquidate`（还款对象与目标线不同），但跳过规则完全一致：停牌 / 跌停封死 / 可卖 0 跳过。
  **催债只在节点提醒，不每天刷屏**（2026-10-13）：结算播报优先级是 到期强卖 > 逾期 > 到期结清 > 临近到期（剩 3 天 / 剩 1 天）> 利率涨档，其余日子静默；
  少发提醒不等于少算利息——每天的 `SHARK_INTEREST` 流水照记（`SharkServiceTest.chattersOnlyOnMilestonesAndStillAccruesEveryDay` 钉住这两件事）。
  另外 `GET /api/loan/shark` 会把额度倍数、起止年化、升档步长、期限、告示文案一并下发，前端只渲染、不在页面里另抄一份规则。
- **三条"排期尾巴"（2026-09-30 修）**：① 纪元 3 长度下限 4 天（`EraScheduler.CRASH_CHAIN_MIN_ERA_LEN`）——`ScriptDayScheduler` 要排"≥2 天千股跌停 → 救市 → 次日千股涨停"，快速模式年长 12 天、抖动会把纪元压到 2 天，股灾就演成一日游、涨停还会漏到下一个纪元的第一格；② 救市日最多排在纪元 3 倒数第二天，保证"救市次日"的涨停留在本纪元内；③ 申购日 ≤ 收官日 - 4（`IPO_LIFECYCLE_TAIL`），给 D+2/D+4 留路。三条都由 `EraSchedulerTest`/`ScriptDaySchedulerTest` 用 200~1000 个种子扫过。
- **快速模式加密度、不砍名场面**（2026-10-10）：黑7月崩盘日的撒点窗口按档走，写在 `GameMode.crashWindowFullEra`（标准 false = 纪元 10 前半段、快速 true = 整个纪元）。500 seed 实测：改前快速模式 60% 的局只有 1 个崩盘日（纪元 10 只剩 2 天，前半段只剩 1 天，掷到 2~3 都被夹成 1），改后最少 2 个、掷到 3 就落 3；名场面日占比标准 16.8% / 快速 31.2%。唯一残留的夹逼在股灾链——快速模式纪元 3 恒为 4 天，"跌跌救涨"排满整段，第 3 个跌停日没有格子（再给纪元 3 加一天就有别的纪元跌破 `minEraLen`）。两条都写死成 `ScriptDaySchedulerTest` 的断言，标准模式的分布改前改后逐 seed 相同（不影响老存档）。
- **一局一档案（GameMode）**：标准/快速两套节奏在 `game/model/enums/GameMode.java` 里各自带值（年长、跳毫秒、午休、涨跌停、最短纪元长度），建局时选定写进 `user_game_state.game_mode`，**一局一选、本局进行中不可改**：注册不绑模式，开局前（大盘页状态条的模式选择器）与终局后（"再来一局"旁的选择器）都走 `POST /api/game/restart {mode}`；restart 允许"终局后"与"从未开过局"两种时机，其余一律拒绝。**不要**再用 `GameConstants.TICK_MS/DAYS_PER_YEAR/TOTAL_DAYS` 做运行时判定——那些只是标准模式的默认值，运行时一律 `GameMode.of(gs.getGameMode())`。调度器改成 500ms 心跳（`SCHEDULER_HEARTBEAT_MS`）+ 每账户按本局 `tickMs` 门控（`QuoteBoard.lastTickAtNanos`）；快进与测试直接调 `tickOne`，不看墙钟，所以"快进=逐跳"等价与同种子可复现都不受影响。前端从 `/api/market/state` 读 `mode/tickMs/daySeconds/totalDays/limitPct`，模式清单（代码/名称/一句话说明/天数）由 `GET /api/game/modes` 下发给 `ModePicker`，注册不绑模式。
- **提醒消息只有一个出口**：任何"要告诉玩家一句话"的通知（纪元切换、结算播报、除权、追保/强平、打新、剧本收官、称号解锁、NPC 台词）一律走 `event/NotifyPublisher`（`system(...)` / `npc(...)`，字段惯例 type=NOTIFY、未读、当日、created_at 现取）。别再在各服务里 `new UserMessage()` 抄一份——历史上抄了 7 份，字段还各有出入。行情事件（EVENT/三态传闻）仍走 `EventFactory`/`MainScriptService`，它们要写 sector/truth_status/followup_of，语义不同。
- **消息分级只有一个口径**：重要性由服务端判——`event/NotifyPublisher.alert(...)` 落 `type=ALERT`（追保、强制平仓、爆仓、高利贷到期强卖与逾期、停牌潮：都是"钱已经出事或马上出事"），其余提醒仍走 `system(...)`/`npc(...)` 落 `type=NOTIFY`。前端 `constants/messageMeta.js#channelOf` 只认 `type === 'ALERT'` → **MODAL**（全屏模态 + 必须亲手点掉），不再按标题关键词猜重要性。收盘晚报的提醒栏同时收 NOTIFY 与 ALERT，别让最高一档反而从总结里消失。
- **暂停闸门（MODAL 的配套）**：`QuoteBoard.paused` 是纯内存标记，只有 `MarketScheduler` 在推进前读它（连午休计时一起冻）；快进、结算、开盘管线与随机协议都不看它，所以 P5「快进=逐跳」与 P6「同 seed 同结果」不受影响，也不持久化（重启回到未暂停，避免世界被冻在无人看管的暂停态）。接口 `POST /api/market/pause|resume` 幂等；`GameLayout` 挂载时若没有待看弹窗会自动 resume 一次——这是防止"玩家刷新页面后世界永久卡住"的安全阀。
- **当日口径字段必须跨日重拉**：WS 快照只有 `{tickIndex,indexValue,quotes:[{stockId,current,changePct}]}`；开高低、涨跌停价、连板、停牌、ST、封板**只来自 REST `GET /api/stocks`**。所以交易日一变就先 `quotes.invalidateDayFlags()` 清掉昨天的结论再重拉（`GameLayout` watch `market.currentDay`），大盘页与个股页的 2 秒轮询也顺带校正一次——否则会出现"今天跌停 -20%，行还是昨天的涨停封板红底"。别把这些字段塞进 WS：一次 24 只票 × 每跳，白白放大协议。
- **日K 的显示口径（照证券软件）**：`KlineChart` 默认**一屏 40 根**（`DEFAULT_BARS`，窄容器按"每根不低于 8px"回收，1440 屏 ≈ 40 根、426 面板 ≈ 28 根），`barWidth: '70%'` 让蜡烛挨着铺满；根数不足一屏时**左侧补空槽**（不是把仅有的几根摊满全屏）。别退回"每根固定 9px"的算法：那样 1440 屏能放 88 根，而一整局最多 82 根，永远塞得下，滑块也就永远没有可拖余量，"拖滑块看历史"根本不可能发生。绘图区宽度靠 `ResizeObserver` 量，且**必须在图表元素出现后再挂**（`v-else` 出来的节点在 `onMounted` 时还不存在，量到 0 就会一路用兜底宽度）。纵向不用 `scale: true`：它会把当前窗口的价差铺满整个高度，一根蜡烛或全天横盘时就是"那线爆炸高"；改成 `yAxis.min/max` 回调按可见区间留 8% 白 + `yPad()` 的最小跨度兜底。滚轮 = `zoomOnMouseWheel` 改宽度，图上按住拖动 = `moveOnMouseMove` 平移。**底部滑块是纯进度条**：`handleSize: 0` 把两端把手关掉（只留 `moveHandleSize` 的小抓手），因为留着把手时玩家一拉两端就把窗口拉到"全部挤成一条"或"缩到一根铺满屏"的极端观感；缩放只走滚轮，并且两个 dataZoom 都设了 `minSpan: 8`（最少看见 8 根，永远滚不出单根巨无霸）。
- **玩家的缩放/平移要留得住，但绝不能在手势中回灌**：`option` 是 computed，任何重算（新 K 线落库、容器宽度变化、滚动条出现或消失）都会重新 `setOption`；`dataZoom.start/end` 若每次都由默认值算出来，玩家拖到的位置会被一把打回。修法是 `@datazoom` 里把窗口记成 **{看几根 span, 距右端 rightGap}** 存进 `userWindow`，重算时按它换算。**但这个变量必须是普通 `let`，不能是 `ref`**：拖动滑块时 ECharts 每帧都发 datazoom，回写响应式状态会立刻触发 `setOption`，等于在它手活半途抢着重设内部拖拽状态——用户看到的就是"滚动条不平移，反而左右把 K 线拉大缩小"。实测（1440 视口、70 根、默认窗口 40 根）：拖窗口体 455→457px 且左移 350px（平移）；拖端点把手 457→199px（缩放）；视口 1440→1200→1440 来回改后窗口仍停在 422–878（位置留得住）。
- **日K 是"前复权"口径**（`MarketQueryService.forwardAdjusted`）：库里 `stock_daily_bar` 存当日**真实成交价**（历史价不该被后来的公司行动改写，所以不加复权列），下发时以最新一根收盘为锚、沿 `change_pct` 往回链式还原连续序列，用「还原价 ÷ 原始收盘」同乘该根 OHLC；`change_pct` 原样透传（它是收益率，对拆股天然免疫，所以能当唯一还原依据）。图上必须标「前复权」，否则玩家以为价格错了。**除权折算走 `Quote.rebaseToday(mapper)`，一次做齐昨收/开/高/低/现价/均价/分时缓冲**，并由 `CorporateActionService.persistToday` 把行情板的五个价双写回 `user_stock_state`——漏掉 open/high 就会画出"腰斩大阴线"配 `-10%` 的自相矛盾图；漏掉分时缓冲就会让当日分时线在除权那一跳凭空断崖。
- **两个 ECharts 坑**：candlestick 的空位必须给 `'-'`（给 `null` 直接抛 `Cannot read properties of null`，整张图不画）；别再把 `start:0/end:100` 写回去。
- **测滚轮缩放别信自动化工具**：`agent-browser mouse wheel` 合成的事件根本进不了 DOM（在 canvas 上挂 capture 监听数出来是 0），所以"工具里滚轮没反应"不能当成 bug，也不能当成 fix 生效的证据；`mouse down/move/up` 是能送达的，滑块拖拽就是这么验通的（默认 40 根窗口下拖左：x 轴从 `第3日…第39日` 变成 `T-9…第30日`）。
- **波动量级只看"有机行情"**（`VolatilityDistributionTest`，2026-10-16 重标定、2026-10-19 压平 U 型、**2026-10-20 引擎大改**）：跑完整局 72 天，按 `user_script_day` 把剧本日摘出去，只对剩下的股票天统计 σ / 贴板率 / ±6% 率。**混在一起量必然得到假结论**——千股涨停那几天 24 只票全钉板上，混进平均就是"贴板 11%"，看着像 bug，其实是有名场面。跨种子实测：有机 σ 3.0~3.2%、有机贴板约 4~5%、有机 ±6% 约 6.5~7.2%（含剧本的整体贴板约 10.3~11.7%）。
  **2026-10-20 引擎大改后**（用户反馈"波动太小"+"每只股都很相似"，选了"够刺激"档）：有机 σ **4.7~5.0%**、有机贴板 **9.5~12%**（每天约 2.3~2.9 只票上板）、有机 ±6% 约 22%。要调波动就调 `GameConstants` 里那组常量（SIGMA_STOCK/SIGMA_MKT/SIGMA_SEC、JUMP_PROB/JUMP_K、GARCH_CAP、OU_KAPPA、ERA_DRIFT_SCALE、MAGNET_TRIGGER/MAGNET_DRIFT、U_OPEN/U_OPEN_END/U_MID/U_CLOSE/U_PHASE_AMP、ST_VOL_FACTOR），**改完必须重跑这个测试看数字**，别只看单测绿。
  **三条别再踩的坑**（都是这次大改实测踩出来的）：
  ① **GARCH 的比率必须取平方、分母必须是"当前条件 σ"**。旧公式（线性比率 + 基准 σ 做分母）的不动点是 `k = 0.8k` 这类退化解，k 被压到 0.05~0.4，**个股波动倍数被整段抵消**——你把 `volMult` 调大，σ 纹丝不动。改法见 `VolatilityEngine.updateGarch` 的注释。
  ② **磁吸不能是"一过线就给恒定漂移"**。那等于"只要摸到触发线就必然收在涨停板上"，日 σ 抬到 5% 之后实测每天 4 只票上板。现在吸力随贴近度线性增强（触发线处 0、板上满档），`SealMachineTest` 钉住这个形状。
  ③ **均值回归会系统性压平下午**。`OU_KAPPA` 的力随偏离变大而变强，开盘几乎不拉、收盘拉得最狠，于是日内方差一路衰减（"爆发起点"有 38% 挤在开盘那一段）。调小到 0.035 后回归仍看得见，但形状交给每只票自己的相位。
- **个股差异三件套**（`StockDef.volMult` / `volPhase` / `policyBeta`，2026-10-20）：24 只票不再共用一条曲线。`volMult` 0.55~1.70（银行/煤炭死水、算力/券商疯）、`volPhase` 0~89 跳且 24 只互不相同（决定这只票一天里哪一段最活跃）、`policyBeta` 0.60~1.50（全市场政策消息落地时这只票反应多大）。实测个股日 σ 从 3.2% 到 7.4%（离散比 2.3 倍）。
- **"齐动度"是独立于波动量级的指标，有自己的回归闸门**（`VolatilityCoMovementTest`，2026-10-20 新增）：量三件事——① 两只票日内轨迹的皮尔逊相关（跨全部票对取平均）；② 去掉市场代理后的残差相关；③ 每只票每天"最大一段 10 跳变动"发生在哪个时段、9 个时段里最挤的那个占多少。玩家说的"都在固定时间突然大涨或大跌一轮"就是这三项超标。改前：rawCorr 0.33、残差 0.12、最挤时段占 38%；改后：**0.12 / 0.00 / 15%（均匀分布）**。**这条闸门和"波动够不够大"是两回事**——σ 抬上去会把共性一起抬起来，必须一起看。
- **全市场政策消息按个股分化落地**（2026-10-20）：`EventSkeletons` 里 6 条 MARKET 作用域事件（降准降息/印花税/国家队/美联储加息/美股暴跌…）幅度 2%~5%，用 `INSTANT` 衰减——旧版**一跳之内给全部 24 只票砸同一个数**，而有机背景只有 0.16%/跳，于是那天盘面上唯一看得见的就是"大家一起跳了一下"。现在 `WorldTickService` 把 `marketShock` 乘以该股的 `policyBeta`：降准那天券商涨得多、银行涨得少，保留"大盘带动"但不再是"集体暴动"。
- **纪元档案的 μ 与板块系数是"相对强度"**：`EraDefs` 里的绝对值不是最终落地值，`EraScheduler` 掷完统一乘 `GameConstants.ERA_DRIFT_SCALE`（现值 0.055）才写进 `user_era.params_json`。所以 `EraSchedulerTest` 的区间断言也要乘这个系数——把它改回原值等于偷偷取消标定。
- **磁吸触发线只认常量**：`SealMachineTest` 里的用例价格必须落在 `MAGNET_TRIGGER` 之上（现值 9%，用例用 +9.5%/-9.5%）。上一版把 6.5% 和 ±7% 抄进测试，抬阈值时全靠改测试才发现漏了哪几处。**注意 ST 票永远够不到这条线**（ST 的板只有 ±5%），所以 ST 票不会进封板分支——这是既有行为，不是这次改出来的。
- **开局前历史 K 是 20 根**（`PreHistoryDefs.BARS`，2026-10-16 从 10 加到 20）：日期 `game_day 0..-19`，走势档案（日均涨跌幅/波动）没改，拉长的是同一条曲线的长度而不是斜率。根数只有一个真源，`MarketQueryService.stockKline` 的上限、`WorldSeeder` 播种、各处断言全部读它——**前端 `fetchStockKline` 的 limit 要给足（现 200）而不是写死刚好够的数**，否则前史一改就悄悄截断最老那几根。
- **令牌对应的用户没了要当没登录，不是 500**：`JwtAuthFilter` 对 `/api/**` 多查一次主键，用户不存在就当未认证（401 → 前端清 token 回入口页）；`QuoteBoardRegistry.getOrLoad` 拿不到 `user_game_state` 抛 `BizException(NOT_FOUND)` 而不是 NPE。便携版这是常规路径：换包 / 删 `data\\\\` / 恢复旧档之后，浏览器里还留着上一个用户的 JWT。前端 401 提示另用 `ElMessage({grouping:true})` 合并并发失败（实测一次进页从 6 条叠层变 1 条）。静态资源不查库（否则每个 js/css 多一次主键查询）。
- **事件量与波动标定是耦合的**（2026-10-16 实测）：骨架从 92 涨到 160 时，有机行情日σ 从 3.09% 被顶到 3.78%——因为新增里多跳漂移型（SLOW_DRIFT / BUY_RUMOR）占比高，每条的累计冲击叠在同一个随机板上。**加事件必须同时看 `VolatilityDistributionTest` 的 σ**，超了就压新事件的幅度（本次把 29 条漂移型骨架幅度统一乘 0.6，σ 回到 3.10%）。另外：`AUTHORITY` 骨架不允许有 `>` 追报（只有传闻/内幕/报道有），写错会让 `EventSkeletons` 静态初始化直接抛异常，炸掉几十个测试。
- **妖股三幕（剧本日）**：`HOT_STREAK`（崩盘前 3 天钉涨停）→ `HALT_SWARM`（崩盘当天集体停牌，**在千股跌停钉板之前**由 `MarketStateService` 前置阶段落地，所以它们不在跌停板上）→ `HUN_DAO`（救市后一字断魂刀）。掷骰走独立 salt `"hotstreak"`，不消费 `"script"` 那条流，所以既有名场面的日期一字不变；复牌补跌复用 `resumeHaltedStocks`，不另写一套停牌。
- **便携版没有账号**（`world/LocalPlayerService` + `web/LocalGameController`，都带 `@Profile("portable")`）：入口页先 `GET /api/auth/local/status`，拿到 200 就渲染"继续游戏 / 创建新游戏"，404 才渲染登录注册。本地玩家是 `sys_user` 里用户名 `单机玩家` 的一行，密码是随机 BCrypt（谁都登不进去），进入只发免密 JWT。"创建新游戏"走 `GameEndService.restart(userId, null, true)`——第三个参数 `allowInProgress` 是便携版专用：玩家已经在警告框里确认过清空，这时"本局还没结束"不能成为拒绝理由。**默认档仍走两参重载**，进行中重开照旧报 `GAME_NOT_FINISHED`；`LocalEntryIsPortableOnlyTest` 用反射钉住这几个 Bean 必须带 `@Profile("portable")`，免得免密入口漏到线上。
- **心跳与冷用户节流**：`MarketScheduler` 每 `SCHEDULER_HEARTBEAT_MS`(100ms) 扫一遍在线用户，但**先做内存预判**（`dueForBeat`）：有行情板的按本局 `tickMs` 判到点；没有板的（未开过局 / 刚重启 / 刚登出）按 1 秒节流放行一次。别去掉这个节流——每个停在"还没开盘"页面的在线账号都会变成每秒 10 次 `selectOne`。
- **前端菜单/路由**：`GameLayout.vue` 竖排菜单；`/market /stock/:id /assets /messages /leaderboard /summary /result`；涨跌与金额格式化统一在 `frontend/src/utils/format.js`。
- **Element Plus 只用四个组件，且必须走深层路径**：全站只用 `el-dialog` + `el-input-number` + `ElMessage`/`ElMessageBox`，按「用到哪个 import 哪个」引 `element-plus/es/components/<x>/index.mjs`——从桶口 `element-plus` 取会把全量组件拖进来（实测只降到 808 kB）。样式在组件内引 `.../style/css`，两个命令式的在 `main.js` 统一引。新增 `el-*` 时记得同处补 `style/css`，否则功能正常但没样式。

## 4. 环境与运维

要求：JDK 17、Node.js、MySQL 8（开发库 `hold_year`、测试库 `hold_year_test`，账号见 `src/main/resources/application.properties`）。Shell 用 PowerShell 7（`pwsh`），禁用 Windows PowerShell 5.1。

| 操作 | 命令（仓库根） |
| --- | --- |
| 新库初始化 | 建库后对 `hold_year`、`hold_year_test` 各执行一次 `src/main/resources/db/schema.sql` |
| 改了表结构 | 重建两个库：`DROP DATABASE hold_year; CREATE DATABASE hold_year DEFAULT CHARACTER SET utf8mb4;` 后 `source src/main/resources/db/schema.sql`（`hold_year_test` 同理）。**本项目不维护增量迁移脚本**，`schema.sql` 是唯一权威 |
| 启动后端 | `.\mvnw.cmd spring-boot:run`（8080） |
| 启动前端 | `cd frontend; npm run dev`（5173，`/api`、`/ws` 代理到 8080） |
| 后端测试 | `.\mvnw.cmd test` |
| 前端构建 / 本地预览生产包 | `cd frontend; npm run build` / `npm run preview` |
| **打包成一个 jar（给朋友玩）** | `game.cmd build`：前端构建 → 拷进 `src/main/resources/static/`（已 gitignore）→ `mvnw package -DskipTests` |
| **启动打包产物** | `game.cmd`（内部就是 `java -jar target\Hold-Hundred-Year-0.0.1.jar`）；此后**只要 8080 一个端口**，不再起 vite |
| 局域网地址 / 防火墙提示 | `game.cmd lan` |
| 备份 / 还原存档 | `game.cmd backup` / `game.cmd restore backups\xxx.sql`（先 `$env:HOLD_DB_PASSWORD`） |
| **打「解压即玩」绿色包** | `pack-portable.cmd` → `dist\hold-hundred-year-portable-<版本>.zip`（jpackage app-image，捆 JRE + 前端 + portable 档） |
| 朋友不在同一网络 | `cloudflared tunnel --url http://localhost:8080`（免费免注册，自带 HTTPS/WSS；网址每次重启会变，拿到网址的人都能进） |

**绿色包的窗口（2026-10-15，实测过）**：`config/PortableAppWindow` 挑浏览器的顺序是**包内自带 `browser\chrome.exe` → `portable.browser-path` → 系统 Edge/Chrome**，用 `--app=` 开无地址栏的独立窗口，并给一个专属 `--user-data-dir`（`~/.hold-hundred-year/window`）。
  - 实测：系统 Edge 有"启动加速"，会把新窗口交给后台已有的 Edge 进程，**我们 spawn 的那个进程 0 秒就退出**，所以"关窗即退出"用系统浏览器不可靠（窗口照常，只是退出得靠 `停止游戏.bat`）。自带 Chromium 时进程归我们，`waitFor()` 才真的等得到关窗。
  - 因此守护线程必须带"存活不足 5 秒 = 误启动/被接管，不许带走服务器"的兜底；这条兜底同时兜住"玩家已经开着一个游戏窗口又点了一次图标"。
  - 别指望用 `ProcessHandle.allProcesses()` 轮询窗口是否还在：本机实测 444 个进程的 `info().commandLine()` 全为空，读不到别人进程的命令行。
  - 配置目录**按浏览器分开**（`~/.hold-hundred-year/profile/<exe名>`）：实测 Edge 与 Chrome 共用一个 `user-data-dir` 时，后启动的会把窗口交给先启动的实例，我们的子进程 0 秒退出，"关窗即退出"当场失效——排查时那段"另一个游戏窗口可能已经开着"的日志就是这个假象。
  - 正常退出时由 JVM shutdown hook 只 `destroy()` 我们 spawn 的那个进程。**绝不允许按映像名 taskkill chrome.exe**：自带浏览器也叫 chrome.exe，那样会把玩家自己的 Chrome 全杀掉（`停止游戏.bat` 就只杀 `hold-hundred-year.exe`）。
  - 找不到浏览器 → 退回默认浏览器 → 再不行只打印地址；三级降级都不许让游戏起不来。只在真起了 Web 服务器时才弹窗口（判据用环境里的 `local.server.port`，别用 `instanceof` 某个 Web 上下文类——Boot 4 把它挪走了）。
  - `pack-portable.cmd` 最后有**冒烟测试**：真的启动一次包里的 exe 并轮询 HTTP，起不来就整包失败退出。这一步是必需的——"配置写错导致进程秒退"这类问题在 jar 层测试里完全看不出来（实测过一次：Logback 滚动文件名缺日期 token → 启动器只报 `Failed to launch JVM`）。冒烟用的存档/日志写在 `smoke-data\`，压包前会删掉，不会带进发布物。成品实测 282 MB（不带浏览器 82.6 MB）。
  - 分发用浏览器只认 **Chrome for Testing**（`npx @puppeteer/browsers install chrome@stable`，国内加 `--base-url https://cdn.npmmirror.com/binaries/chrome-for-testing`）。Playwright 的 Chromium 是自编译构建，依赖目标机器装过 VC++ 运行库，实测未装时报 `CreateProcess error=14001`（并行配置不正确），发出去就是打不开。
  - **自带浏览器窗口顶部那条"仅适用于自动测试，请使用可自动更新的标准版 Chrome"必须用 `--disable-infobars` 关掉**（2026-10-16 实测 A/B 截图：同一份 chrome.exe、同一个 `--app=` 窗口，不带这条就有横幅，带了就干净）。它是 CfT 的**构建横幅**，不是"版本过期"提示——所以 `--simulate-outdated-no-au` 治不了它（那条治的是后者，仍然留着：绿色包会长期流传，构建总有过期的一天）。普通 Chrome/Edge 不认 `--disable-infobars`（CfT 从 121 起自己实现了它），传过去只是被忽略。**改这条命令行必须同时改 `PortableAppWindowTest`，并且要真的截图看窗口**：横幅是浏览器自己的 UI，DOM 里读不到，前端自动化测不出来。
  - **验自带浏览器别在 `%LOCALAPPDATA%\*` 下跑**：本机实测把同一份 CfT 放 `%LOCALAPPDATA%\任意目录\chrome-win64` 一律 `CreateProcess error=14001`（并行配置不正确），放 `%TEMP%` 或桌面就正常起——**文件字节完全相同**（SHA-256 逐一对过），所以是这台机器的目录策略，不是包坏了。要复现玩家看到的窗口，请把包解压到桌面/文档这类普通目录再启动。
**便携包不随代码更新**：`dist\\*.zip` 只在"升版本号 + 明确要求打包"两个条件同时满足时重新生成（AGENTS.md 落地约定）。日常提交只动源码与文档。

**两种存储、一份 schema（2026-10-15）**：默认档连本机 MySQL（学习目标就是它的行锁/事务，测试基线也跑在它上面）；`--spring.profiles.active=portable` 切到内嵌 H2 文件库（`config/PortableDataSourceConfig` 在 DataSource 工厂方法里建表，保证任何 Mapper 拿到连接时表已就绪；存档就是 `./data/hold_year.mv.db` 一个文件）。建表脚本仍然只有 `db/schema.sql` 一份：H2 侧由 `common/MysqlSchemaForH2` 翻译三处方言差异（`ENGINE=...` 尾巴、行内 `INDEX` 拆成 `CREATE INDEX IF NOT EXISTS`、索引/约束名从"表内唯一"改成加表名前缀的全库唯一）。翻译器有条测试兜着：**别把行首 `--` 注释当成整段丢弃**——`schema.sql` 第一行注释和 `CREATE TABLE sys_user` 同属一段，整段跳过的后果是第一张表不存在。全量 457 条测试在 portable 档（H2）下也是全绿的，包括 100 线程并发买入与快进等价。

**给别人玩的鉴权面（2026-10-15）**：`SecurityConfig` 改成只有 `/api/**`（除 `/api/auth/**`）需要登录，其余 `anyRequest().permitAll()`。原因：单个 jar 同时发前端页面和接口，浏览器直接访问或刷新 `/market`、`/stock/3` 这类 history 路由都得拿到 index.html（兜底在 `config/WebStaticConfig`，它必须让 `api/`、`ws/` 前缀原样落空，否则接口报错会被伪装成"首页能打开"）。以前写的是 `anyRequest().authenticated()`，jar 里嵌的前端会被整页挡成 401。别把这条改回去，也别放开 `/api/**`。配置全部支持环境变量覆盖（`HOLD_DB_URL` / `HOLD_DB_USER` / `HOLD_DB_PASSWORD` / `HOLD_PORT` / `HOLD_JWT_SECRET` / `HOLD_JWT_TTL_HOURS`），默认值就是本机开发那套；`game.cmd` 是配套的启动/打包/备份脚本。

**绿色包的日志是必需的，但 Logback 的滚动文件名必须带日期**：portable 档把日志写到 `data\\hold-year.log`（双击启动没有控制台，朋友那边出问题只能靠这个文件回传）。踩过的坑：给 `logging.logback.rollingpolicy.file-name-pattern` 写了个只有 `%i` 的模板，Logback 直接抛 `FileNamePattern [...] does not contain a valid DateToken` → 日志系统初始化失败 → 进程秒退 → **jpackage 启动器只弹一句 "Failed to launch JVM"**，完全看不出是配置问题。教训两条：① 只设 `max-file-size`/`max-history`，滚动模板交给 Spring Boot 默认（自带 `%d{yyyy-MM-dd}`）；② 绿色包改动必须真跑一次包里的 exe 验收，光测 jar 挡不住这一层。

**坑⑥：只跑 `mvn package` 打出来的 jar 里嵌的是旧前端**。前端的落点是 `src/main/resources/static/`（已 gitignore），
只有 `game.cmd build` 会做「npm build → 删 static → 拷 dist」这三步；直接 `mvn package` 会把上一次拷进去的旧包再打一遍，
界面改了却看不出来（实测踩过一次：新加的便携版入口页在浏览器里仍是旧登录页，而接口 `/api/auth/local/status` 明明是 200）。
手工等价命令：`Remove-Item src/main/resources/static -Recurse -Force; Copy-Item frontend/dist src/main/resources/static -Recurse`，
之后再 package。

**坑**：① 测试无 H2 兜底，`hold_year_test` 不存在会大面积报 `Unknown database`；② 旧库不执行迁移会让 `user_game_state` 读写报 `Unknown column`；③ 前端依赖后端，先起后端；④ `mvnw.cmd` 内部调用 Windows PowerShell 5.1，某些机器会死在 `(Get-Item ~/.m2).Target[0]`（报 `Cannot index into a null array` / `Cannot start maven from wrapper`）——可直接用 wrapper 已下载的 Maven：设好 `JAVA_HOME` 后执行 `& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\apache-maven-*\bin\mvn.cmd" test`；⑤ 本机 `localhost` 解析到 IPv6，用 `127.0.0.1` 探端口/代理可能看起来"拒绝连接"，浏览器与脚本请用 `localhost`。

**回归防护**：任何触及开盘管线 / 结算 / 引擎的改动，必跑 `VolatilityEngineTest`、`VolatilityDistributionTest`、`VolatilityCoMovementTest`、`FastForwardServiceTest`、`TradeConcurrencyTest`、`WorldTickServiceTest`，并复验"快进与正常推进同种子结果一致"。**改波动常量必须同时看 `VolatilityDistributionTest`（量级）与 `VolatilityCoMovementTest`（共性）两条线**——σ 抬上去会把共性一起抬起来，只看其中一条会把另一个问题做坏。

## 5. 当前验收基线

- **后端 `test` = 461 tests / 0 Failures / 0 Errors**（需本机 `hold_year_test`；portable 档 H2 下同样全绿）。覆盖：同种子可复现、快进=逐跳、并发守恒、结算 12 步、追保/强平/爆仓、高利贷额度与递增计息与到期强卖、事件骨架 160 条与 `event-copy.txt` 一一对应、**有机σ 4.8% 的分布闸门**、**齐动度闸门（跨股相关 0.12、残差 0.00、爆发时段均匀）**、**股吧按票成流（热度曲线 + 闲聊兜底 + 按票冷却）**、文案文件全部登记且跨池零重复、剧本日撒点不死循环、`prev_index` 基准、三类流水查询、模式档案、提醒消息惯例（含 `ALERT` 档）、心跳节流、暂停闸门（暂停中一跳不推进、恢复后续走）、开局前历史 K（20 根、day 0 收盘=发行价、同 seed 同形状、单日不越涨跌停）、妖股三幕（连板钉涨停 / 停牌潮先于千股跌停钉板且名单一致 / 断魂刀落在救市后）、`restart` 空 body 沿用本局模式、终局两结局。
- **股吧产出（2026-10-20 大改后实测，3 个交易日）**：每票每天约 **25 条**、热门票 30~45 条、闲聊占一半以上（改前：全市场一天 30 条、每票 1~2 条）。文案库：日常闲聊池 250+ 条、每票专属弹幕 16 条。
- **波动标定（2026-10-20 引擎大改后，固定世界种子三局平均）**：有机日 σ **4.83%**（闸门 4.2%~5.8%）、有机贴板 **10.4%**（闸门 < 12%，约每天 2.5 只票上板）、有机 ±6% **22.1%**（闸门 < 26%）、个股日 σ **3.2%~7.4%**（离散比 2.3 倍）、跨股日内轨迹相关 **0.12**（改前 0.33）、残差相关 **0.00**（改前 0.12）、日内最大一段变动的时段分布 **9 段各占 14%~16%**（改前 38% 挤在开盘）。
- **前端 `npm run build` 通过**：入口主包 19.04 kB、`vendor-element` 86.65 kB（按需引入，勿回退）。
- **除权除息与日K复权已实测**：`CorporateActionServiceTest` 断言 10转10/现金分红把昨收·开·高·低·现价·均价·分时缓冲**整体**折算并双写回 `user_stock_state`；`MarketQueryServiceTest` 用"第 1 日收 170 → 第 2 日除权收 84.15 → 第 3 日 +5%"的三日剧本断言日K连续（第 1 根复权到 85 口径、图内跳幅 == tooltip 涨跌幅、最新一根仍是真实成交价）。
- **便携版入口已实机走查（2026-10-16，portable 档真跑 jar + 浏览器）**：新机器 status 报无存档 → 「继续游戏」置灰 → 「创建新游戏」直接进第 1 日（右上角显示`单机玩家`，模式仍在状态栏选）→ 退出后入口页显示「第 1 / 72 个交易日、净资产 ¥100,000.00、标准模式」→ 再点「创建新游戏」弹“存档将被清空”，点「取消」留在入口页不清档，点「继续游戏」回到 /market。
- **实机走查基线**：注册 → 开盘 → 行情/弹幕/消息 → 买卖（T+1、涨跌停、佣金）→ 融资 → 高利贷 → 打新 → 排行 → 日报 → 双结局 → 再来一局，全程 console error 0、非预期接口错误 0。
- **2026-10-13 游戏性批次已实机复核**（新号走查）：日K 开局即有前史（当时 10 根、现为 20 根，见上条；蜡烛等宽 + 底部滑块）；跨日后行情牌的今开/最高/最低/封板都是当日值（第 2 日涨停票挂"封板"、跌 6% 的票无残留涨停红底）；侧栏默认展开；票名已是新化名。
- 截图只保留**当前版本**（`docs/design/shots/`）：界面改版后重截替换，不叠加历史截图。
- **变更历史不在本文档里**：`git log` 与 tag `v0.0.1` 是唯一记录。

## 6. 还值得做的事（都不是缺陷）

- **倍速按钮（1x/2x/4x）**：跳毫秒已经是每局档案里的值（`GameMode.getTickMs()`），再做倍速只需把它降级成「每账户运行时参数 + 档案默认值」，并让午休计时（`lunchStartedNanos` 用挂钟）与状态栏倒计时随动。随机序列按跳消费，倍速**不影响**同种子可复现性。
- **生产化打包/部署说明**：当前只有开发模式运行手册，无部署文档。做之前要先定「部署给谁」——自用 / 局域网 / 公网三档的工作量差一个数量级（配置分档、HTTPS/WSS、进程守护、备份都只在公网档才需要）。

## 7. 文案维护（改文案不用改代码）

全部游戏文案在 `src/main/resources/content/` 七个纯文本文件里，**中文一行一条**，改完重启后端即生效；格式故意不用 JSON/YAML，避免引号与转义把文案搞成代码。

| 文件 | 管什么 | 分组写法 |
| --- | --- | --- |
| `danmu.txt` | 股吧弹幕：9 个全局类别池（涨停/跌停/急拉/跳水/大跌/大涨/横盘…）+ 21 个板块池 | `@LIMIT_UP`、`@SECTOR:白酒` |
| `stock-personas.txt` | 24 只票的绰号、股性、专属弹幕（8 条）、专属事件文案（6 条） | `@5:DANMU`（数字 = 股票 id） |
| `sector-flavor.txt` | 21 板块 + 大盘的权威公告/媒体报道/股吧传闻/玩梗 | `@白酒:RUMOR`，条目 `标题\|正文[\|追报]` |
| `npc-lines.txt` | 4 个角色线场景台词 + 8 位 NPC 的性格闲聊 | `@SCENE:DAMA_RECOMMEND`、`@CHATTER:DAMA` |
| `event-copy.txt` | 160 条事件骨架的标题/正文变体与证实·辟谣追报 | `@fund-big-order`，条目 `标题\|正文` / `sector:白酒\|标题\|正文` / `>追报` |
| `era-narratives.txt` | 11 个纪元的叙事（纪元切换通知的正文） | `@BULL_START`（枚举名，每组恰好一行） |
| `shark-lines.txt` | 高利贷的 7 个场景台词（借到手 / 每日催债 / 到期 / 逾期 / 强卖 / 结清 / 板块告示） | `@CHATTER`、`@WARNING` |

规则：`#` 开头是注释、空行忽略、`@分组名` 开一个分组、其余每行一条。**分组缺失或为空会在启动时直接抛错**（宁可起不来，也不要运行时静默没文案）。

槽位：`{stock}` 换成股票名（仅个股类可用）、`{amount}` 换成金额、`{user}` 换成 NPC 或玩家名。禁 emoji（有测试拦）。

改完必跑 `mvn test`，这些测试就是文案的 lint：

- `ContentCatalogTest`：目录里每个 .txt 都必须已在 `ContentCatalog.loadAll()` 登记（新增文件忘了登记会红）。
- `StockPersonasTest`：股数/板块覆盖齐、跨全部文案池**零重复**（重复凑数会红）、无 emoji、无"占位/TODO"。
- `EventSkeletonsTest`：骨架与 `event-copy.txt` 分组**一一对应**、每条骨架 ≥2 变体、槽位白名单、追报与来源匹配。
- `SharkLinesTest`：7 个场景各有话、彼此不重复、无 emoji 无占位，且同种子同一天抽到同一句（催债话术走 `WorldRandom`，不许 `Math.random`）。
- `EraNarrativesTest`：11 个纪元都有且只有一句叙事、分组名与 `EraCode` 枚举名一一对应（打错字的分组不会被任何代码读到，会红）、不重复也不复读纪元标题。

注意：**增删文案条目会改变同种子下的抽取结果**（随机数按序消费）。引擎行为、可复现性与"快进=逐跳"等价都不受影响，但老存档用同一个种子 replay 出的事件/弹幕序列会和以前不同——这是改文案的预期代价，不必为它做兼容分支。
