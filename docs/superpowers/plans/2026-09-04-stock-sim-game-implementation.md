# 《你持有一百年看看》实现计划（Implementation Plan）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按规格 v7 实现"三年 108 个交易日"的 A 股模拟炒股游戏：Spring Boot 行情/交易/融资后端 + Vue 3 漫画贴纸风前端。

**Architecture:** 单仓 Maven 多模块思路（单 Spring Boot 应用，包内分层）：`world`（世界种子/排期）、`market`（行情引擎/状态机/调度）、`trade`（交易/融资）、`event`（事件工厂/NPC/弹幕）、`ws`（WebSocket）、`web`（REST）。行情内存权威 + MySQL 持久化快照；每账户一把世界锁互斥调度与快进；全部随机数源自 `world_seed` 保证可复现。

**Tech Stack:** Java 17、Spring Boot 3.x、MyBatis-Plus、MySQL 8、Spring Security + JWT、Spring WebSocket(STOMP)、JUnit5；前端 Vue 3 + Vite + Element Plus + ECharts + Pinia；无 Redis/MQ。

**规格依据:** [2026-09-04-stock-sim-game-design.md](../specs/2026-09-04-stock-sim-game-design.md)（v7）、[style-tile.html](../../design/style-tile.html)

***

## 0. 痛点与教训总结（设计阶段已确认，实现时必须遵守）

| #   | 痛点                   | 结论/约束                                                                                                   |
| --- | -------------------- | ------------------------------------------------------------------------------------------------------- |
| P1  | 节奏太慢                 | 1 天=5 分钟=90 跳（3s/跳），上午 45 跳→午休 30s→下午 45 跳；36 天/年×3=108 天                                               |
| P2  | 纯随机无叙事、纯剧本太公式        | 11 纪元**顺序与全覆盖硬保证**，边界 ±1\~3 天抖动、强度区间随机，注册时掷点入库                                                          |
| P3  | 随机游走不像股市             | 三层因子（市场/板块/个股 β）+ GARCH-lite 波动聚集 + 肥尾跳跃 + 日内 U 型 + 涨跌停磁吸/封板/炸板                                         |
| P4  | 多冲击源叠加会写成 if-else 地狱 | 统一 `PriceShock` 队列：冲击只入队，引擎每跳统一消费（衰减曲线决定反应模式）                                                           |
| P5  | 快进与在线推进结果必须一致        | `WorldTickService.tickOne(userId)` 单一推进函数；调度器与 skip 快进共用；每账户 `ReentrantLock` 互斥                         |
| P6  | 不可复现/重开无差异           | `world_seed`(long) 入库，全世界随机数走 `new Random(seed ^ salt)`；测试同种子逐跳一致                                       |
| P7  | 事件线性模板重复感            | 组合式事件工厂：~~70 骨架 × 2~~6 行业变体 × 槽位随机；弹幕 150+ 条                                                            |
| P8  | 中期无目标                | 营业部 NPC 排行榜、资产段位、阶段挑战徽章、新手引导                                                                            |
| P9  | 内存态重启丢失              | Quote/Shock/剧本内存态；重启由 `user_stock_state` 快照 + `game_event` 未完结事件重建；封板按价位重判                              |
| P10 | 并发交易超卖/资不抵债          | 交易：`SELECT ... FOR UPDATE` 悲观锁（user+position 顺序加锁）；行情：`version` 乐观锁；100 线程压测守恒                          |
| P11 | emoji 风格不统一          | **全项目禁用 emoji**；功能图标=SVG sprite（墨色描边/currentColor）+ element-plus icons；复杂形象=AI 生成 PNG；三态=文字徽章；涨跌=CSS 色块 |
| P12 | 前端"AI 感"             | 禁紫蓝渐变/玻璃拟态；米白纸底 #f6f1e7、2px 墨色描边、3px 硬阴影、站酷快乐体标题、同花顺同款正文字体栈、红涨绿跌                                        |
| P13 | 每日历史数据缺口             | `user_daily_snapshot` 每日收盘落库（资产/指数/NPC 资产），供曲线/排行榜/终局                                                   |
| P14 | T+1/停牌/ST/除权规则易漏     | available\_shares 卖出后次日解冻；halted 冻结；ST 涨跌停 ±5%；除权在开盘首跳前处理                                               |
| P15 | 融资爆仓判定               | 担保比例=(现金+市值负债)/(负债)；<140% 追保、<120% 强平（跌停封死顺延）、资不抵债→BANKRUPT                                             |

**通用工程约定**：TDD（先写失败测试→实现→通过→提交）；每个 Task 末尾提交；常量集中在 `game/constants` 包；金额用 `BigDecimal`（行情内部用 double，落库/交易用 BigDecimal，分=0.01 四舍五入 HALF\_UP）；所有时间用游戏日+跳数，不用墙钟（调度器除外）。

***

## 1. 文件结构（后端 `src/main/java/com/holdhunderdyear/`）

```
HoldHunderdYearApplication.java
config/        SecurityConfig, WebSocketConfig, MybatisPlusConfig, CorsConfig
common/        R(统一响应), BizException(错误码枚举), ErrorCode
game/
  constants/   GameConstants(跳数/利率/阈值), EraDefs(11纪元基准+漂移区间),
               StockDefs(24只股票:代码/名/板块/beta/权重), EventSkeletons, DanmuTexts, NpcDefs, Titles
  model/       enums: MarketState, EraCode, ScriptType, Source, TruthStatus, ImpactPattern,
                      VolRegime, LoanType, Tier, IpoStatus, SealState
               dto: *DTO（请求/响应，含 DailySummaryDTO, LeaderboardDTO, GameResultDTO）
world/
  WorldSeeder.java          注册播种：种子→纪元表→剧本日/IPO→快照0
  EraScheduler.java         纪元边界抖动掷点（纯函数，可单测）
  ScriptDayScheduler.java   剧本日/IPO 落点掷点（纯函数）
  WorldRandom.java          种子随机源工厂：randomFor(seed, salt)
market/
  entity/... + mapper/...   MyBatis-Plus 实体与 Mapper
  quote/Quote.java          内存行情（价格/封板/garchK/缓冲）
  quote/QuoteBoard.java     该用户 24 只 Quote + 指数 + 世界锁 + 内存 Shock/剧本状态
  engine/PriceShock.java    冲击（目标/总幅/剩余跳/衰减曲线）
  engine/ShockQueue.java    按股票维护活跃冲击
  engine/VolatilityEngine.java  三层因子+GARCH+跳跃+U型+磁吸判定
  engine/SealMachine.java   封板/炸板状态机
  engine/IndexCalculator.java  百年指数
  WorldTickService.java     tickOne(userId)：一跳完整推进（核心）
  MarketStateService.java   状态机：开盘/午休/下午/结算/熔断/终局
  MarketScheduler.java      @Scheduled 3s 扫描在线开盘世界 tryLock→tickOne
  FastForwardService.java   skip：持锁循环 tickOne 到收盘
  SettlementService.java    收盘结算：T+1/计息/快照/打新/除权预排/称号/结局
trade/
  TradeService.java         买卖（悲观锁+费用+T+1+校验）
  MarginService.java        借款/还款/计息/担保比例/追保/强平/爆仓
  CorporateActionService.java 除权除息/ST 戴帽摘帽
  IpoService.java           打新申购/摇号/连板结算
event/
  EventFactory.java         骨架抽取→实例（变体/槽位）→消息+事件+Shock
  EventSkeleton.java        骨架定义（record）
  MessageService.java       消息落库/三态/辟谣关联
  MainScriptService.java    主力剧本（洗盘/出货/天地板）
  HaltService.java          停牌/复牌
  NpcService.java           NPC 收益函数/排行榜/角色线消息
  DanmuService.java         弹幕文案触发
  ProgressService.java      段位/徽章/新手引导
ws/
  WsSessionRegistry.java    在线用户会话
  PushService.java          quotes/messages/notifications/danmu 推送
web/
  AuthController, MarketController, TradeController, LoanController,
  IpoController, MessageController, GameController
```

**前端** **`frontend/`（Vite + Vue3）**：`src/api`（axios+JWT）、`src/stores`（pinia: user/market/quotes/messages）、`src/components`（StatusBar/TickerTape/DanmuBar/QuoteTable/SealStamp/StickerTag/SvgIcon/NpcAvatar/TierBadge）、`src/views`（Login/Market/StockDetail/Assets/Messages/Leaderboard/DailySummary/GameResult）、`src/assets/icons.svg`（sprite）、`src/styles/tokens.css`（设计令牌）。

***

## Phase 0 — 工程基线

### Task 0.1: 后端依赖与配置

**Files:**

- Modify: `pom.xml`

- Modify: `src/main/resources/application.properties`

- Create: `src/main/resources/schema.sql`（Phase 1 建表，先建空文件占位不提交空）

- [ ] **Step 1: 确认 pom.xml 依赖**：spring-boot-starter-web/security/websocket/validation、mybatis-plus-spring-boot3-starter（3.5.7+）、mysql-connector-j、jjwt（0.12.x）、lombok、spring-boot-starter-test。`mvn -q compile` 通过。

- [ ] **Step 2: application.properties**：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/hold_year?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=root
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
mybatis-plus.configuration.map-underscore-to-camel-case=true
mybatis-plus.global-config.db-config.logic-delete-field=deleted
server.port=8080
app.jwt.secret=hold-year-dev-secret-change-in-prod-0123456789
app.jwt.ttl-hours=24
```

- [ ] **Step 3: 建库**：`mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS hold_year DEFAULT CHARSET utf8mb4;"`（测试库 `hold_year_test`）。

- [ ] **Step 4: Commit**：`chore: backend deps and datasource config`

### Task 0.2: 通用响应与异常

**Files:** Create `common/R.java`, `common/ErrorCode.java`, `common/BizException.java`, `common/GlobalExceptionHandler.java`

- [ ] **Step 1: 写失败测试** `src/test/java/com/holdhunderdyear/common/RTest.java`：`R.ok(1).getCode()==0`；`BizException(ErrorCode.LIMIT_UP_CANNOT_BUY)` 被 GlobalExceptionHandler 转成 `R.fail(code,msg)`。

- [ ] **Step 2: 实现**：`R{int code; String msg; T data}` 静态 `ok()/ok(data)/fail(code,msg)`；ErrorCode 枚举（UNAUTHORIZED, MARKET\_CLOSED, NOT\_LOT, LIMIT\_UP\_CANNOT\_BUY, LIMIT\_DOWN\_CANNOT\_SELL, HALTED, INSUFFICIENT\_CASH, INSUFFICIENT\_SHARES, STOCK\_NOT\_FOUND, MARGIN\_EXCEED, REPAY\_EXCEED, FINISHED\_STATE, NOT\_TRADING\_TIME 等，每条带中文文案）。

- [ ] **Step 3: 测试通过 → Commit**：`feat: unified response and business error codes`

***

## Phase 1 — 数据库与领域模型

### Task 1.1: DDL 建表脚本

**Files:** Create `src/main/resources/db/schema.sql`

- [ ] **Step 1: 写 14 张表 DDL**（字段严格按规格 §9）：`sys_user(id,username,password,balance,debt,created_at)`、`stock_info(id,stock_code,stock_name,sector,base_price,beta,index_weight)`、`user_stock_state(id,user_id,stock_id,prev_close,open_price,high_price,low_price,current_price,limit_up_streak,halted,halt_until_day,is_st,gap_pct,version, unique(user_id,stock_id))`、`user_position(id,user_id,stock_id,shares,available_shares,avg_cost,unique(user_id,stock_id))`、`trade_record(...)`、`loan_record(id,user_id,loan_type,amount,debt_after,game_day,created_at)`、`game_event(id,user_id,stock_id,scope,script_type,event_type,impact_pattern,title,content,impact_pct,game_day,created_at)`、`user_message(id,user_id,source,truth_status,type,title,content,stock_id,followup_of,is_read,game_day,created_at)`、`user_game_state(id,user_id unique,current_day,market_state,tick_index,world_seed,index_value,prev_total_asset,vol_regime,tier,end_reason,end_day,updated_at)`、`user_title(id,user_id,title_code,title_name,kind,game_day,created_at,unique(user_id,title_code))`、`user_era(id,user_id,era_index,era_code,start_day,end_day,params_json,unique(user_id,era_index))`、`user_script_day(id,user_id,game_day,script_type,params_json,unique(user_id,game_day,script_type))`、`user_daily_snapshot(id,user_id,game_day,cash,market_value,debt,net_asset,day_return_pct,index_value,index_day_pct,npc_assets_json,unique(user_id,game_day))`、`user_ipo_record(id,user_id,script_day_id,ipo_name,issue_price,status,profit,game_day,created_at)`。

- [ ] **Step 2: 执行**：`mysql -uroot -proot hold_year < src/main/resources/db/schema.sql` 验证无错。

- [ ] **Step 3: Commit**：`feat: database schema (14 tables)`

### Task 1.2: 实体与 Mapper

**Files:** Create `market/entity/*.java`（14 个 @TableName 实体，Lombok @Data）、`market/mapper/*Mapper.java`（extends BaseMapper）

- [ ] **Step 1: 生成实体**（MyBatis-Plus 注解 @TableId(AUTO)、@Version 加在 user\_stock\_state.version、枚举字段用 String 存）。

- [ ] **Step 2: Mapper 接口** + `@MapperScan("com.holdhunderdyear.**.mapper")` 加到启动类。

- [ ] **Step 3:** **`mvn test`** **上下文加载测试通过（连库）→ Commit**：`feat: mybatis-plus entities and mappers`

### Task 1.3: 24 只股票静态档案

**Files:** Create `game/constants/StockDefs.java`

- [ ] **Step 1: 写测试** `StockDefsTest`：共 24 只；stock\_code 唯一；beta 全在 \[0.6,1.6]；指数权重和≈1（±0.02）；板块覆盖：券商/银行/保险/白酒/家电/医药/半导体/算力/通信/传媒/锂电/光伏/新能源车/物流/地产/煤炭石油/食品/农业/军工/制造/水产/家电 等（按规格 §2 股票池：片爷科技/通天信联/智算无穷/大通银号/鸱夷酒业/极乐传媒/汪洋水产/回春堂/万间置业 等化名）。

- [ ] **Step 2: 实现**：`record StockDef(long id,String code,String name,String sector,double basePrice,double beta,double weight)`，`List<StockDef> ALL` 静态常量；β 分配：券商/科技妖股 1.3~~1.6，白酒/银行/煤炭 0.6~~0.9。

- [ ] **Step 3: 测试通过 → Commit**：`feat: 24 stock definitions with beta and index weights`

***

## Phase 2 — 世界种子与排期器（纯逻辑，重点单测）

### Task 2.1: WorldRandom 种子随机源

**Files:** Create `world/WorldRandom.java`

- [ ] **Step 1: 测试**：`randomFor(123L,"era")` 两次产生序列相同；不同 salt 序列不同。

- [ ] **Step 2: 实现**：

```java
public final class WorldRandom {
    public static Random of(long seed, String salt){ return new Random(seed ^ salt.hashCode() * 0x9E3779B97F4A7C15L); }
    private WorldRandom(){}
}
```

- [ ] **Step 3: Commit**：`feat: seeded random source for reproducible worlds`

### Task 2.2: EraScheduler 纪元抖动

**Files:** Create `world/EraScheduler.java`, `game/constants/EraDefs.java`

- [ ] **Step 1: EraDefs**：11 纪元 record（eraIndex, code, 基准 start/end 年·日, μMkt 区间\[min,max]（每跳）, 板块系数 Map\<sector,系数区间>）。μ 标定：疯牛 +0.0010/跳（日 +9% 上限由剧本日接管）、股灾 -0.0012、熔断 -0.0020、黑7月科技板块额外 -0.0015、慢牛 +0.0003、熊市 -0.0004、震荡 0。

- [ ] **Step 2: 写失败测试** `EraSchedulerTest`（用固定 seed）：
  - 11 个纪元全覆盖、eraIndex 1..11；

  - 同年边界不重叠、不留空档（前一个 endDay+1 = 后一个 startDay，按绝对游戏日）；

  - 每纪元长度 ≥3 天；纪元不跨年（年1 纪元在 1..36，年2 在 37..72，年3 在 73..108）；

  - 纪元 5（熔断）startDay 固定=该年第 1 天、长度 3\~5；

  - 100 个不同 seed 生成的时间表，至少 90 个互不相同；

  - params\_json 含 μMkt 与板块系数且在声明区间内。

- [ ] **Step 3: 实现**：

```java
public List<EraInstance> roll(long seed){
  Random r = WorldRandom.of(seed,"era");
  // 年1 分界基准（绝对日）：10.5→11, 20.5→21, 28.5→29；抖动 ±3/±2/±2
  // 年2：纪元5固定 start=37 长度 3~5；白马/贸易战分界基准 36+20=56 ±3
  // 年3：分界 72+12=84 ±2, 72+24=96 ±2, 72+32=104 ±1
  // 掷分界点 → 排序 → 裁剪最短时长 → 构造 EraInstance(start,end,params)
}
```

边界用"先掷年分界点→同年按顺序赋纪元→强制 minLen 裁剪"算法，保证约束。

- [ ] **Step 4: 测试通过 → Commit**：`feat: era scheduler with jitter and strength randomization`

### Task 2.3: ScriptDayScheduler 剧本日与 IPO

**Files:** Create `world/ScriptDayScheduler.java`

- [ ] **Step 1: 写失败测试**：
  - 千股跌停日 2~~3 次且**首次**在纪元3 第 1~~2 天内；救市日 1 次在纪元3 后半段；千股涨停日 = 救市次日 + 纪元2 内 1\~2 次；

  - 熔断日 2 个：纪元5 第 1、2 交易日（固定）；

  - 制裁闪崩 1~~2 次落在纪元7 内；黑7月崩盘 2~~3 个跌停日落纪元10 前半段；

  - IPO：每年 2~~3 次（共 6~~9 个），params 含 issuePrice（5\~30 元）与连板数由上市时纪元决定（此处只排申购日）；

  - 所有 gameDay 在 1..108；params 含封死比例 0.85\~0.95。

- [ ] **Step 2: 实现**：输入为 EraScheduler 产出的纪元区间（在纪元内 `r.nextInt(len)` 落点，首日锁定规则按测试）；输出 `List<ScriptDayInstance>`。

- [ ] **Step 3: Commit**：`feat: script-day and IPO schedule roller`

### Task 2.4: WorldSeeder 注册播种

**Files:** Create `world/WorldSeeder.java`, `market/entity` 配合

- [ ] **Step 1: 写集成测试** `WorldSeederIT`（test 库，@SpringBootTest）：注册用户后断言——user 存在 balance=100000/debt=0；game\_state（day=1,NOT\_STARTED,seed 非 0,tier=RETAIL）；24 行 stock\_state（current=base\_price,is\_st=false）；11 行 era；script\_day 数量满足 Task 2.3；snapshot 第 0 天 1 行（net\_asset=100000,npc 全 100000）。

- [ ] **Step 2: 实现**：`@Transactional seedUser(username,bcryptPassword)`：掷 seed→插 user/game\_state→批量插 stock\_state→调 EraScheduler/ScriptDayScheduler 落库→snapshot day=0。

- [ ] **Step 3: Commit**：`feat: world seeder with era/script/snapshot bootstrap`

***

## Phase 3 — 认证

### Task 3.1: JWT + Spring Security

**Files:** Create `config/SecurityConfig.java`, `web/AuthController.java`, `common/JwtUtil.java`, `game/model/dto/{LoginDTO,RegisterDTO}.java`

- [ ] **Step 1: 测试**：注册→登录拿 token→无 token 访问 /api/me 401→带 token 200；重复注册 400。

- [ ] **Step 2: 实现**：SecurityConfig 放行 `/api/auth/**` 与 ws 端点，其余 authenticated；JwtUtil 用 jjwt 签发/解析 userId；AuthController 调 WorldSeeder。

- [ ] **Step 3: Commit**：`feat: jwt auth and registration with world seeding`

***

## Phase 4 — 行情引擎（核心难点，完整代码）

### Task 4.1: Quote 与 QuoteBoard（内存世界）

**Files:** Create `market/quote/Quote.java`, `market/quote/QuoteBoard.java`

- [ ] **Step 1: Quote 字段**：stockId, prevClose, open, high, low, current, limitUpStreak, halted, isSt, `SealState sealed`(NONE/UP/DOWN), `int sealTicksLeft`, `double garchK`(初值1), `double dayAvg`, `Deque<double[]> intraday`(90 点 \[price,index?] 只存 price)，方法 `limitUpPrice()`（isSt? prevClose\*1.05 : \*1.10，HALF\_UP 分）、`limitDownPrice()`、`dayChangePct()`。

- [ ] **Step 2: QuoteBoard**：`Map<Long,Quote> quotes`、`double indexValue`、`double prevIndex`、`ReentrantLock lock`、`ShockQueue shocks`、`Map<Long,MainScriptState> scripts`、`int tickIndex`、从 stock\_state 快照 `restore(userId)`。

- [ ] **Step 3: Commit**：`feat: in-memory quote board with world lock`

### Task 4.2: PriceShock 与 ShockQueue

**Files:** Create `market/engine/PriceShock.java`, `market/engine/ShockQueue.java`

- [ ] **Step 1: 测试**：一个总幅 6%、6 跳、线性衰减的 shock，每跳消费返回 1%，6 跳后移除；同股两个 shock 叠加；MARKET 级 shock 作用于全部股票。

- [ ] **Step 2: 实现**：

```java
public record PriceShock(long stockId, boolean marketWide, double totalPct,
                         int totalTicks, int ticksLeft, Decay decay) {
    public enum Decay { ONCE, LINEAR, EXP }
    public double consume(){
        if(ticksLeft<=0) return 0;
        double v = switch(decay){
            case ONCE  -> ticksLeft==totalTicks ? totalPct : 0;
            case LINEAR-> totalPct/totalTicks;
            case EXP   -> totalPct * 0.5/totalTicks * 2; // 简化：前重后轻，实现时按 totalPct*(remainingFrac)/sumFrac
        };
        return v; // 调用方 ticksLeft--
    }
}
```

`ShockQueue.consume(stockId)`：汇总该股票专属 + marketWide 活跃 shock 的本跳幅度并递减；过期移除。

- [ ] **Step 3: Commit**：`feat: price shock queue with decay curves`

### Task 4.3: VolatilityEngine（三层因子 + GARCH + 跳跃 + U 型）

**Files:** Create `market/engine/VolatilityEngine.java`, `game/constants/GameConstants.java`

- [ ] **Step 1: GameConstants**：TICKS\_PER\_SESSION=45, TOTAL\_TICKS=90, TICK\_MS=3000, LUNCH\_MS=30000；σ\_STOCK=0.0035, σ\_MKT=0.0018, σ\_SEC=0.0012；VOL\_REGIME={CALM:0.6,NORMAL:1.0,WILD:2.0}；JUMP\_PROB=0.04, JUMP\_K=3\~5；MAGNET\_TRIGGER=0.065；COMMISSION/STAMP\_TAX/MARGIN 等（交易/融资用）。

- [ ] **Step 2: 写测试**（统计性，固定 seed 跑 2000 跳）：
  - β=1.5 的股票收益对市场因子回归系数显著 > β=0.7；

  - 同板块两只股票收益相关性 > 跨板块；

  - 注入一个 5σ 大跳后，后续 5 跳平均波动 > 前 5 跳（GARCH 聚集）；

  - 开盘前 10 跳/尾盘 10 跳波动均值 > 中间跳（U 型）；

  - 日收益 |r|>5% 的频率显著高于同方差正态（肥尾，用正态 CDF 对比断言）。

- [ ] **Step 3: 实现每跳基础收益（返回不含封板/磁吸的 rawReturn）**：

```java
public double rawReturn(Quote q, StockDef def, EraInstance era, int tick, Random rng){
  double uShape = uShapeFactor(tick);                 // 0~10:1.6→0.9; 11~79:0.8; 80~89:1.4
  double regime = GameConstants.VOL_REGIME.get(volRegime);
  double zMkt = rng.nextGaussian();
  double rMkt = era.muMkt() + GameConstants.SIGMA_MKT * regime * uShape * zMkt;
  double rSec = GameConstants.SIGMA_SEC * regime * uShape * zSector.get(def.sector());
  double garch = q.garchK();                          // 由上一跳更新
  double zI = rng.nextGaussian();
  double r = def.beta()*rMkt + rSec + GameConstants.SIGMA_STOCK * regime * uShape * garch * zI;
  if(rng.nextDouble() < GameConstants.JUMP_PROB){
      int k = 3 + rng.nextInt(3);
      r += GameConstants.SIGMA_STOCK * k * rng.nextGaussian();
  }
  return r;
}
// 跳后更新：k' = 0.85*k + 0.15*min(|prevR|/SIGMA_STOCK, 4)
```

板块 zSector 每跳每板块一个 gaussian，存引擎当跳上下文。

- [ ] **Step 4: Commit**：`feat: three-factor volatility engine with garch/jump/u-shape`

### Task 4.4: SealMachine 磁吸/封板/炸板

**Files:** Create `market/engine/SealMachine.java`

- [ ] **Step 1: 测试**：
  - 涨幅 ≥6.5% 时注入磁吸后 20 跳内封板概率显著高于 <6.5%；

  - 封板后 current==limitUp 且 `canBuy()==false`；封板 5~~30 跳后掷炸板，炸板产生 -2%~~-4% 冲击（入 ShockQueue ONCE）；

  - 连板 streak≥2 的磁吸/封板概率 > streak=0；

  - 一字板（gap≥9.5%）开盘首跳即 sealed；

  - 跌停方向对称。

- [ ] **Step 2: 实现**：`apply(Quote q, int tick, Random rng)`：若 sealed!=NONE 且 sealTicksLeft-->0 则钉价；到期掷炸板（概率 0.35，否则续封到收盘 sealTicksLeft=剩余跳）；未封板且 |dayChange|≥0.065：加磁吸漂移（±0.0015/跳，入返回值），并按概率封板（基础 0.06/跳，streak 加成 +0.02/streak，触发位越近越高）；封板 sealed=UP/DOWN、sealTicksLeft=5+rng.nextInt(26)。

- [ ] **Step 3: Commit**：`feat: limit magnet and seal/break state machine`

### Task 4.5: IndexCalculator

**Files:** Create `market/engine/IndexCalculator.java`

- [ ] **Step 1: 测试**：全部股票涨 1%，指数≈+1%（权重和 1）；权重股涨 10% 影响 = weight×10%。

- [ ] **Step 2: 实现**：`indexValue = prevIndex * Π_i (1 + w_i * r_i)`（每跳累乘），或加权平均收益法 `index *= (1+Σw_i r_i)`；用后者更稳。

- [ ] **Step 3: Commit**：`feat: centennial index calculator`

***

## Phase 5 — 状态机、调度、快进、结算

### Task 5.1: MarketStateService 状态机与开盘

**Files:** Create `market/MarketStateService.java`

- [ ] **Step 1: 测试**：NOT\_STARTED→startDay()→MORNING\_OPEN（应用 gap\_pct 跳空/除权/ST 处理/剧本日强制行为预标记/纪元切换消息）；45 跳后→LUNCH\_BREAK（推午休消息）；再→AFTERNOON\_OPEN；90 跳后→SETTLING→CLOSED（或 FINISHED）；非法转换抛 BizException；start 幂等。

- [ ] **Step 2: 实现开盘流程**：加载/重建 QuoteBoard→读当日 script\_day→应用隔夜 gap（夹逼涨跌停，|gap|≥9.5% 标记一字板）→除权除息（CorporateActionService）→纪元切换检测（推叙事消息）→剧本日强制行为（千股涨停/跌停按封死比例抽股直接 sealed、熔断标记）→波动体制掷点（马尔可夫：CALM/NORMAL/WILD 转移矩阵，WILD 续发概率高）→反向意外日 8% 掷点→NPC/引导消息。

- [ ] **Step 3: Commit**：`feat: market state machine and day-open pipeline`

### Task 5.2: WorldTickService.tickOne（核心一跳）

**Files:** Create `market/WorldTickService.java`

- [ ] **Step 1: 写测试**（固定 seed）：
  - tickOne 90 跳后价格在涨跌停内、high≥current≥low、缓冲 90 点；

  - 停牌股价格不变；封板股价格钉死；

  - 熔断日上午指数 ≤-7% 当跳状态转 SETTLING（提前收盘）；

  - 同一 seed 两个世界各跑 30 跳，24 只价格序列完全一致；

  - tickOne 会触发事件掷骰（每 10\~20 跳）与弹幕判定。

- [ ] **Step 2: 实现**：

```java
public void tickOne(Long userId){
  QuoteBoard board = boardRegistry.getOrLoad(userId);
  board.lock().lock();
  try{
    int tick = board.tickIndex();
    EraInstance era = eraService.currentEra(userId, currentDay);
    // 1. 每跳上下文：zSector by sector
    // 2. 对每只未停牌、未封板股票：
    //    rawReturn = volatilityEngine.rawReturn(...)
    //    + shockQueue.consume(stockId)
    //    + sealMachine.magnetDrift(...)
    //    剧本日强制（千股跌停封死股直接钉跌停并 sealed=DOWN）
    //    newPrice = clamp(current*(1+r), limitDown, limitUp) HALF_UP
    //    sealMachine.apply(...) 更新封板/炸板（炸板 shock 入队下跳生效）
    //    更新 high/low/dayAvg/garchK/缓冲
    // 3. 封板股钉价；停牌股跳过
    // 4. indexCalculator 更新；熔断判定（era=5 且上午 index/prevIndex-1 ≤ -0.07 → 提前收盘）
    // 5. eventFactory.maybeRoll(...)（按跳间隔）、danmuService.maybePush(...)
    // 6. tickIndex++；持久化价格快照（每跳或每 5 跳批量 update user_stock_state，version 乐观锁）
    // 7. 在线则 pushService.pushQuotes(userId, snapshot)
    // 8. 阶段转换：45 跳→午休；90 跳→settlementService.settle(userId)
  } finally { board.lock().unlock(); }
}
```

- [ ] **Step 3: Commit**：`feat: single-tick world advancement (tickOne)`

### Task 5.3: 调度器与快进

**Files:** Create `market/MarketScheduler.java`, `market/FastForwardService.java`

- [ ] **Step 1: 测试**：
  - 调度器对 ONLINE 且开盘世界每 3s tickOne（用 awaitility 验证 tickIndex 增长）；

  - `tryLock` 失败时跳过本轮（持锁线程占用时调度器不推进、不阻塞）；

  - skip：盘中跳过→跑完剩余跳到收盘；CLOSED 态跳过→自动开盘跑到收盘；熔断日跳过触发提前收盘；第 108 天跳过→FINISHED；

  - **快进与正常推进等价性**：同 seed，世界 A 逐跳 tickOne 90 跳，世界 B 直接 skip，断言 24 只收盘价、指数、消息集合、snapshot 完全一致。

- [ ] **Step 2: 实现**：Scheduler `@Scheduled(fixedDelay=3000)` 遍历 WsSessionRegistry 在线用户，开盘态 `if(board.lock.tryLock()) try{tickOne}finally{unlock}`；FastForwardService.skip 持锁循环 `while(开盘中) tickOne`（tickOne 内部已含阶段转换与结算；午休在快进中直接跳过 30s 等待并触发午休消息）。

- [ ] **Step 3: Commit**：`feat: 3s scheduler and fast-forward skip with shared tick path`

### Task 5.4: SettlementService 收盘结算

**Files:** Create `market/SettlementService.java`

- [ ] **Step 1: 测试**（集成）：
  - 收盘后 prevClose=current、availableShares=shares（T+1 解冻）、连板 streak 更新（收盘涨停 +1 否则 0）、gap\_pct 掷出（±3% 常态，剧本/事件加大）；

  - 融资计息（debt × 0.08/36/36？按规格：年化 8%，按日计提 debt \*= 1+0.08/360 或 /252——用 /252 交易日，落 INTEREST 流水）；

  - snapshot 落库（资产/指数/NPC 资产由 NpcService 计算）；

  - 打新 T+2 摇号/上市结算触发；除权预排事件落 game\_event；

  - 段位/徽章/称号检测；追保/强平判定（MarginService）；爆仓→BANKRUPT；第 108 天→FINISHED。

- [ ] **Step 2: 实现**：按规格 §3 结算步骤 1\~12 顺序实现；日收益率 = netAsset/prevTotalAsset-1。

- [ ] **Step 3: Commit**：`feat: end-of-day settlement with snapshots and t+1 release`

***

## Phase 6 — 交易

### Task 6.1: TradeService 买卖

**Files:** Create `trade/TradeService.java`, `web/TradeController.java`

- [ ] **Step 1: 写测试**：
  - 买入：整手校验（100 股）、费用（佣金万 2.5 最低 5 元、卖出印花税千 1）、余额扣减、持仓 avgCost 加权、available 不增加（T+1）；

  - 卖出：available\_shares 扣减、shares 扣减、资金增加；

  - 拒单：休市/涨停封死买/跌停封死卖/停牌/ST 正常交易但 ±5%/资金不足/股数不足/非整手，各自 ErrorCode；

  - 并发：100 线程并发买同一只，最终 balance≥0、shares×price+cash 守恒（允许佣金损耗）、无死锁。

- [ ] **Step 2: 实现**：`@Transactional` 方法内 `SELECT ... FOR UPDATE` 锁 sys\_user（再按 stockId 排序锁 position 防死锁）；价格取 QuoteBoard 内存 current；成交写 trade\_record；推送 trade-result。

- [ ] **Step 3: Commit**：`feat: buy/sell with t+1, fees, pessimistic lock and seal checks`

***

## Phase 7 — 融资爆仓与结局

### Task 7.1: MarginService

**Files:** Create `trade/MarginService.java`, `web/LoanController.java`

- [ ] **Step 1: 测试**：
  - 借款 ≤ 净资产（debt+borrow ≤ 净资产？规格：可借净资产×1：borrow ≤ netAsset-debt 即总负债≤净资产），现金增加、debt 增加、落 BORROW；

  - 还款 ≤ debt，落 REPAY；

  - 担保比例 = (cash+marketValue)/debt（无负债返回极大）；<1.4 追保通知、<1.2 强平；

  - 强平：按持仓市值从大到小卖出（跌停封死跳过顺延下跳/次日），还款直至比例≥1.4 或无股可卖；资不抵债（netAsset<0 或无股且比例<1）→ BANKRUPT 结局；

  - 强平落 LIQUIDATE 流水。

- [ ] **Step 2: 实现**：强平在结算阶段与盘中（每跳后检查）均可触发；封死跌停卖不出时标记待强平，下跳重试。

- [ ] **Step 3: Commit**：`feat: margin borrowing, margin call and forced liquidation`

### Task 7.2: 双结局与重开

**Files:** Modify `SettlementService`, Create `web/GameController.java`

- [ ] **Step 1: 测试**：108 天结算→FINISHED+end\_reason=NORMAL\_FINISH；构造负债暴跌→BANKRUPT；`/api/game/result` 返回收益率/指数/段位/曲线/最终排行榜/种子号；`/api/game/restart` 清用户游戏数据并以新 seed 重新播种（保留账号与密码）。

- [ ] **Step 2: Commit**：`feat: dual endings (bankrupt/finish), result page and restart`

***

## Phase 8 — 事件系统

### Task 8.1: 事件骨架与工厂

**Files:** Create `event/EventSkeleton.java`, `event/EventFactory.java`, `game/constants/EventSkeletons.java`

- [ ] **Step 1: 定义骨架 record**：

```java
public record EventSkeleton(String code, Scope scope, Source source, Set<EraCode> eras, // 空=全纪元
                            double weight, TargetSelector target, ImpactPattern pattern,
                            double[] impactRange, int[] tickRange, double truthProb,
                            List<String> variantTexts, List<String> followupTexts) {}
```

- [ ] **Step 2: 写测试**：骨架数 ≥70；每个骨架 variantTexts ≥2 且文案无 `{` 未填充槽位泄漏（槽位 {amount}/{stock}/{user} 填充后断言不含 `{`）；纪元标签过滤（制裁骨架不在非贸易纪元抽出，抽 5000 次断言）；truthProb 抽样：传闻类证实率≈0.45、媒体≈0.80、内幕≈0.70（±0.05）。

- [ ] **Step 3: 实现工厂**：`maybeRoll(userId,board,tick)`：间隔掷骰（10~~20 跳）→按纪元+scope 过滤骨架→权重抽取→选目标（持仓股加权 2x/NPC 荐股全市场）→选变体→填槽位→写 user\_message+game\_event→按 impactPattern 生成 PriceShock（INSTANT=ONCE、SPIKE\_FADE=正向 LINEAR+反向 EXP、BUY\_RUMOR=小 LINEAR+证实日 ONCE 反向、SLOW\_DRIFT=长 LINEAR、OVERSHOOT\_REVERT=ONCE+次日反向 ONCE、NEWS\_FLIP、CHAIN=多日 ONCE）；三态定时器（1~~3 天后 followup 消息，PENDING→CONFIRMED/DENIED）。

- [ ] **Step 4: Commit**：`feat: combo event factory with 70 skeletons and 7 impact patterns`

> 骨架内容按规格 §7.3 五池填充：池①基本面~~35、池②板块政策~~20、池③传闻~~20（骨架计 70，文案变体分布到各骨架）；池④玩梗~~30 与池⑤NPC 走独立常量类（Task 8.3/8.4），不占骨架数但文案库总量 ≥500 变体。

### Task 8.2: 消息与三态

**Files:** Create `event/MessageService.java`, `web/MessageController.java`

- [ ] **Step 1: 测试**：消息落库/筛选/已读；辟谣 followup\_of 关联；PENDING 到期揭晓（证实/辟谣/不了了之）；来源枚举含 FUN/NPC（无三态）。

- [ ] **Step 2: Commit**：`feat: message center with truth lifecycle`

### Task 8.3: 玩梗弹幕与股吧弹幕

**Files:** Create `event/DanmuService.java`, `game/constants/DanmuTexts.java`

- [ ] **Step 1: 测试**：弹幕文案 ≥150 条且无 emoji（正则断言）；急拉/封板/跌停/横盘触发对应类别文案；弹幕不进 user\_message（仅推送 /user/queue/danmu）。

- [ ] **Step 2: 实现**：`maybePush(userId,board)`：按当跳行情事件（|r|>3%、sealed、炸板、指数大跌、冷门股成交）抽文案，彩色股吧 ID 随机。

- [ ] **Step 3: Commit**：`feat: danmu feed with 150+ texts`

### Task 8.4: NPC 角色线与 @玩家事件

**Files:** Create `event/NpcService.java`, `game/constants/NpcDefs.java`

- [ ] **Step 1: 测试**：
  - 张大妈荐股后该股 65%（±0.08）走弱（荐股即入负向 shock + 消息）；

  - 老李"停车场满"消息在 volRegime=WILD 当日开盘触发；

  - 老王角色线：黑7月纪元首周后状态=天台排队中（排行榜 npc\_assets\_json 标记）；

  - @玩家事件：追涨停买入后当日收盘推"龙虎榜游资一日游"；跌停卖出次日该股涨停推"割肉还好吗"；每日至多 1 条。

- [ ] **Step 2: NPC 收益函数**：每个 NPC 一个策略画像（β、换手倾向、踩点偏误），日收益 = β\_npc×rMkt + 风格漂移 + 噪声（seeded），落 snapshot.npc\_assets\_json；老王/新基民在黑7月按规则"销户"（资产定格+状态标记）。

- [ ] **Step 3: Commit**：`feat: npc characters, leaderboard pnl functions and player-reactive events`

### Task 8.5: 主力剧本与停牌

**Files:** Create `event/MainScriptService.java`, `event/HaltService.java`

- [ ] **Step 1: 测试**：洗盘剧本完成后价格创新高、出货剧本完成后破位（<启动价）；天地板单跳从涨停到跌停；停牌：连板≥3 或制裁事件触发 halted，halt\_until\_day=1\~3 天后复牌，复牌补跌 gap；停牌期间跳过推进。

- [ ] **Step 2: Commit**：`feat: main-force scripts (shakeout/distribution/sky-earth) and trading halt`

***

## Phase 9 — A 股特色机制

### Task 9.1: ST 戴帽/摘帽与除权除息

**Files:** Create `trade/CorporateActionService.java`

- [ ] **Step 1: 测试**：
  - 戴帽：立案/巨亏事件后 is\_st=true，涨跌停价变 ±5%，股名前缀 ST；15\~25 日内扭亏事件摘帽恢复 ±10%；每股每局至多 1 次；

  - 10 转 10：shares/available 翻倍、avgCost/prevClose/current 减半，净资产不变；

  - 现金分红：balance 增加 shares×div，prevClose/current 减 div，净资产守恒。

- [ ] **Step 2: 实现**：除权在开盘首跳前事务处理；事件骨架中加入高送转/分红/戴帽触发。

- [ ] **Step 3: Commit**：`feat: ST hat/unhat and corporate actions (split/dividend)`

### Task 9.2: 打新

**Files:** Create `trade/IpoService.java`, `web/IpoController.java`

- [ ] **Step 1: 测试**：申购幂等；T+2 摇号中签率 0.20~~0.35（seeded 可复现）；中签缴款（余额不足→ABANDONED）；上市日按当前纪元掷连板数（疯牛 6~~12/结构牛 3~~6/熊市 0~~2 或破发 -10%），开板按 issuePrice×1.1^N（破发 ×0.9）卖出入现金，落 SETTLED+profit；全程不新增 stock\_state。

- [ ] **Step 2: Commit**：`feat: ipo subscription, lottery and limit-up settlement`

***

## Phase 10 — 进度系统与 WebSocket

### Task 10.1: 段位/徽章/引导

**Files:** Create `event/ProgressService.java`, `game/constants/Titles.java`

- [ ] **Step 1: 测试**：段位阈值 15w/30w/60w/100w 升降级（峰值进阶、跌破降级）与通知；徽章（空仓躲过千股跌停/抓住 5 连板/打新中签/连续 10 天不交易等）达成解锁且不给现金；新手引导前 3 天步骤推进、首笔交易解锁"初入股市"。

- [ ] **Step 2: Commit**：`feat: asset tiers, challenge badges and onboarding`

### Task 10.2: WebSocket

**Files:** Create `config/WebSocketConfig.java`, `ws/WsSessionRegistry.java`, `ws/PushService.java`

- [ ] **Step 1: 测试**：STOMP 连接带 JWT（握手拦截器鉴权）；订阅 /user/queue/quotes、messages、notifications、danmu 收到推送；断线移除会话；快进期间不推 quotes。

- [ ] **Step 2: Commit**：`feat: websocket channels for quotes/messages/notifications/danmu`

### Task 10.3: REST 收尾

**Files:** `web/MarketController.java`（state/start/skip/daily-summary/leaderboard/equity-curve）

- [ ] **Step 1: 测试**：各端点返回 DTO 字段齐全；skip 返回 DailySummaryDTO；终局态 skip/交易 400。

- [ ] **Step 2: Commit**：`feat: market rest endpoints (state/skip/summary/leaderboard/curve)`

***

## Phase 11 — 前端脚手架与设计系统

### Task 11.1: Vite + Vue3 骨架与令牌

**Files:** Create `frontend/`（npm create vite\@latest -- --template vue）、`src/styles/tokens.css`、`src/main.js`

- [ ] **Step 1: 依赖**：vue-router、pinia、axios、element-plus、@element-plus/icons-vue、echarts、vue-echarts。

- [ ] **Step 2: tokens.css**：照 style-tile.html 的 `:root` 变量（--paper/--card/--ink/--up/--down/--brand/--orange/--yellow/--seal/--radius/--border/--shadow/--font-body/--font-fun）；Element Plus 主题覆盖（--el-color-primary:#ff2436、border-radius、卡片描边阴影）；站酷快乐体 CDN `@font-face`（Google Fonts ZCOOL KuaiLe，回退系统字体）；全局 `.num{tabular-nums}`。

- [ ] **Step 3: Commit**：`feat(fe): vite scaffold and design tokens`

### Task 11.2: SVG sprite 与基础组件

**Files:** Create `src/assets/icons.svg`（从 style-tile.html 提取 10 个 symbol：chart/play/skip/alert/bell/door/flame/bull/bear/bank）、`src/components/SvgIcon.vue`（`<svg><use :href="...">`，currentColor）、`StickerTag.vue`、`SealStamp.vue`、`TierBadge.vue`、`NpcAvatar.vue`（头像位用 PNG 占位组件，缺图时姓氏色块兜底）。

- [ ] **Step 1: 测试/验证**：组件渲染、颜色继承；**全局 grep 断言 src 内无 emoji 字符**（CI 脚本 `grep -P '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]'` 无结果）。

- [ ] **Step 2: Commit**：`feat(fe): svg icon sprite and sticker-style base components (no emoji)`

### Task 11.3: API 层与 Pinia store

**Files:** Create `src/api/http.js`（axios 拦截器带 JWT/401 跳登录）、`src/api/*.js`、`src/stores/{user,market,quotes,messages}.js`、WebSocket 客户端（STOMP over sockjs 或原生 WebSocket+JSON，订阅四通道断线重连）。

- [ ] **Step 1: 验证**：登录→state→quotes 实时更新。

- [ ] **Step 2: Commit**：`feat(fe): api layer, pinia stores and websocket client`

***

## Phase 12 — 前端页面

### Task 12.1: 登录页

**Files:** `src/views/Login.vue`

- [ ] 营业部大门视觉（AI 生成 PNG 任务见 Task 12.6，先用 i-bank SVG 放大占位）、米白纸底、胶囊按钮、登录/注册切换。Commit：`feat(fe): login page`

### Task 12.2: 大盘页

**Files:** `src/views/Market.vue`、`components/{StatusBar,TickerTape,QuoteTable,DanmuBar,IpoDialog,OnboardingTip}.vue`

- [ ] 状态条（年/日/阶段倒计时/纪元旗标 bull|bear SVG/段位徽章/开始/跳过按钮+二次确认/铃铛未读）；LED 跑马灯（CSS marquee，内容来自 notifications/事件流）；行情表（紧凑 13.5px、红涨绿跌色块、封板 SealStamp、ST 贴纸、停牌封条、火苗连板、炸板文字）；底部弹幕条（可关）；打新弹窗；新手气泡。

- [ ] 验证：实时跳动、封板买不进按钮禁用文案、跳过弹总结。Commit：`feat(fe): market page with ticker tape, quote table and danmu bar`

### Task 12.3: 个股详情页

**Files:** `src/views/StockDetail.vue`（ECharts 分时图叠加指数、买卖面板整手/费用预估/禁用原因、持仓、该股消息流、个股弹幕）。Commit：`feat(fe): stock detail with intraday chart and trade panel`

### Task 12.4: 资产页

**Files:** `src/views/Assets.vue`（资产卡片含担保比例警戒条、融资面板、持仓表、交易/融资/打新流水、108 天 ECharts 资产 vs 指数曲线、称号徽章墙）。Commit：`feat(fe): assets, margin panel and equity curve`

### Task 12.5: 消息中心/排行榜/总结/终局

**Files:** `src/views/{Messages,Leaderboard,DailySummary,GameResult}.vue`

- [ ] 消息：来源筛选、文字三态徽章、NPC 头像、辟谣跳转；排行榜：木榜样式、NPC 风格标签、天台状态；总结：晚报样式（双线标题/斜印章/要闻/点评/明日提示）；终局：奖状（通关）/黑白漫画（爆仓）+曲线+最终榜+种子号+再来一局。Commit：`feat(fe): messages, leaderboard, daily newspaper summary and endings`

### Task 12.6: AI 美术资产

**Files:** `frontend/src/assets/img/`（dama.png/laowang.png/laoli.png/chief.png 头像、bull.png/panda.png 吉祥物——Q 版可爱风、**不靠红绿颜色区分**：牛=暖棕毛+大弯角+宽鼻吻+呆毛；熊市吉祥物是**熊猫「滚滚」**=黑白配色+黑圆耳+圆润黑椭圆黑眼圈+亮晶晶大眼+粉腮红。**吉祥物已有定稿参考图** `docs/design/mascot-bull.jpg` / `mascot-panda.jpg`（Seedream 生成、style-tile 第三节在用）：直接拷贝进 frontend assets，并用同款提示词以 `--no-watermark` 重生成无水印生产版（现有参考图右下角带淡"AI生成"水印）；bank-door.png 登录插画、certificate.png 奖状、rooftop.png 天台）；吉祥物小尺寸场景（纪元旗等）用 icons.svg 里的 bull/bear symbol（bear symbol 画的就是熊猫，id 不改），风格以 style-tile.html 第三节预览为准（验收：18px 剪影下"弯角"与"黑耳+黑眼圈"仍可辨）。

- [ ] 用统一提示词生成："flat vector cartoon, thick dark outline, warm cream paper background, playful Chinese stock market theme, simple shapes, no text, no emoji"；NpcAvatar/登录页/终局页引用。Commit：`feat(fe): ai-generated cartoon art assets`

***

## Phase 13 — 端到端验收

### Task 13.1: 后端全量测试

- [ ] `mvn test` 全绿；重点：EraScheduler/ScriptDayScheduler/VolatilityEngine/SealMachine 统计测试、种子复现、快进等价性、100 线程并发守恒、108 天快进通关、爆仓结局、三局排期不同。

### Task 13.2: 手工联调清单（规格 §13）

- [ ] 登录→引导→开盘→板块齐涨齐跌/券商领涨→买卖→封板买不进/炸板→张大妈反向指标→洗盘→打新→跳过看晚报→隔夜一字板→停牌→ST/除权→融资→千股跌停（重开二号验证排期/事件不同）→爆仓/通关→再来一局。

### Task 13.3: 无 emoji 与视觉回归

- [ ] 前后端全仓 grep emoji 零匹配；前端页面截图对照 style-tile.html 令牌一致（纸底/描边/硬阴影/字体/红涨绿跌）。

***

## 自查记录（Self-Review）

- **规格覆盖**：§3 节奏/状态机/跳过/世界锁→P5；§4 纪元/剧本日/随机→Phase2、5.1；§5 行情引擎全机制→Phase4；§6 交易/融资/特色机制→Phase6/7/9；§7 事件五池/七模式/NPC/弹幕/剧本/停牌→Phase8；§8 消息三态→8.2；§9 数据模型 14 表→Phase1；§10 WebSocket→10.2；§11 API→各 Controller + 10.3；§12 视觉/页面→Phase11/12；§13 测试→Phase13。

- **占位符**：无 TBD/TODO；统计性测试给出了断言方法；骨架文案在 Task 8.1 给出数量与规则（实现时按规格 §7.3 池清单填充，属内容创作非占位）。

- **类型一致性**：`tickOne`/`QuoteBoard`/`PriceShock(decay)`/`SealState`/`ImpactPattern`/`world_seed` 在各任务命名统一；Error 文案与 ErrorCode 枚举对应。

<br />
