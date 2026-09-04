# 《你持有一百年看看》设计规格文档

- 日期：2026-09-04
- 状态：已与需求方确认定稿
- 关联文档：《你持有一百年看看》需求分析.md（初版需求，本规格对其进行细化与修订）

---

## 1. 项目概述与目标

基于 Spring Boot 的微型虚拟股市模拟游戏。后端定时任务驱动行情、WebSocket 实时推送、数据库事务与锁保障交易一致性，用于深度练习 Spring Boot 企业级技术栈。

**核心模式：账户隔离的单机存档游戏。** 每个注册账户拥有一个完全独立的游戏世界：独立的交易日进度、行情序列、突发事件。玩家扮演资金量不影响市场的散户，按当前价与系统成交；除涨跌停板限制外均可成交。各账户之间互不可见、互不影响。

练手目标覆盖：Spring Security + JWT 认证、Spring WebSocket（STOMP）点对点推送、定时调度与线程隔离、MyBatis-Plus、MySQL 悲观锁（SELECT ... FOR UPDATE）与乐观锁（version）、高并发下资金安全验证。

## 2. 技术栈

- **后端**：Spring Boot 4.1.1、Java 17、MyBatis-Plus、MySQL 8（InnoDB）、Spring WebSocket（STOMP 子协议）、Spring Security + JWT（jjwt）、Lombok、spring-boot-starter-validation、springdoc-openapi（接口文档）。
- **前端**：Vue 3 + Vite + Element Plus + Pinia + Vue Router + axios + ECharts（分时图）+ @stomp/stompjs（WebSocket 客户端）。
- **形态**：前后端两个独立工程；后端仅提供 REST + WebSocket。密码使用 BCrypt 加密。
- **线程模型**：Tomcat 线程处理 HTTP 请求；行情/事件/时钟推进运行在独立调度线程池，与 HTTP 线程隔离。

## 3. 游戏时间与市场状态机

时间常量（可配置）：

| 参数 | 值 |
|---|---|
| 行情跳价间隔 | 3 秒 / 跳 |
| 上午盘 | 95 跳（约 4.75 分钟） |
| 午休休市 | 30 秒 |
| 下午盘 | 95 跳（约 4.75 分钟） |
| 一个交易日 | 现实约 10 分钟，共 190 个价格点 |

市场状态机（每个账户独立）：

```
NOT_STARTED（第 1 天待开盘）
   └─(玩家手动"开始第 1 天")→ MORNING_OPEN
MORNING_OPEN（上午盘，行情跳动，可交易）
   └─ 95 跳结束 → LUNCH_BREAK
LUNCH_BREAK（午休 30 秒，行情停跳，不可下单）
   └─ 30 秒结束 → AFTERNOON_OPEN
AFTERNOON_OPEN（下午盘，行情跳动，可交易）
   └─ 95 跳结束 → SETTLING
SETTLING（自动收盘结算，瞬间完成）→ CLOSED
CLOSED（已收盘，不可交易，可查看资产）
   └─(玩家手动"开始下一交易日")→ MORNING_OPEN（current_day + 1）
```

关键规则：

- **手动开盘**：`NOT_STARTED` 与 `CLOSED` 状态下，玩家点击"开始 / 下一交易日"才推进；服务端校验状态，重复点击幂等无效。不存在自动跨天。
- **离线暂停**：游戏时间仅在该账户 WebSocket 在线时推进。断开连接时行情停跳、午休倒计时暂停，状态持久化；重连后从断点继续。
- **收盘结算（SETTLING）自动执行**：
  1. 全部股票当前价快照落库；
  2. `prev_close`（次日涨跌停基准）= 当日收盘价；
  3. T+1 解冻：所有持仓 `available_shares = shares`；
  4. 重置次日 open/high/low（次日首跳以 prev_close 开盘）；
  5. 推送结算通知，状态转 CLOSED。

## 4. 行情引擎

- **内存权威**：在线用户的报价保存在内存，键为 `(userId, stockId)`。每只股票一个 `Quote` 对象：currentPrice、open、high、low、prevClose、limitUp、limitDown，以及当日分时环形缓冲（容量 ≥190 点）。
- **每跳推进**（仅 MORNING_OPEN / AFTERNOON 状态、且用户在线）：
  1. 几何随机游走：`newPrice = price × (1 + μ + σ · Z)`，Z 为标准正态随机数，叠加轻微均值回归项（向 base_price 缓慢牵引）；
  2. 事件引擎可注入方向性漂移（见第 6 节）；
  3. 价格夹逼在 `[跌停价, 涨停价]`，四舍五入保留 2 位小数；
  4. 更新 high/low，追加环形缓冲；
  5. 通过 STOMP 向该用户推送 `/user/queue/quotes`（8 只股票全量）。
- **调度方式**：单线程 ScheduledExecutorService 每 3 秒扫描"在线且开盘中"的用户会话，顺序逐用户推进（单机规模下开销可忽略）。
- **快照落库**：每 30 秒及收盘时，批量 UPDATE `user_stock_state`（current/open/high/low/prev_close），使用 MyBatis-Plus `@Version` 乐观锁，冲突重试至多 3 次；同时将 `user_game_state.tick_index` 一并持久化，用于重启后断点恢复。
- **交易读价**：交易服务直接读内存现价，无数据库 I/O；内存读使用 volatile/CAS 保证可见性。
- 重启恢复：内存行情从 `user_stock_state` 最近快照重建；当日分时环形缓冲以快照价为起点重新累积（重启前的当日分时曲线不保留，属可接受取舍）。

## 5. 交易规则与资产

### 5.1 交易规则

- **整手交易**：买卖数量必须为 100 的整数倍。
- **涨跌停**：涨停价 = round(prev_close × 1.10, 2)，跌停价 = round(prev_close × 0.90, 2)。**涨停时拒买、跌停时拒卖**；反方向不受限。
- **T+1**：买入当日增加 `shares` 但不增加 `available_shares`；收盘结算解冻后才可卖出。
- **交易时段**：仅 MORNING_OPEN / AFTERNOON_OPEN 可下单；午休、收盘、未开盘状态拒单。
- **成交价**：下单瞬间的内存现价。玩家资金量不影响市场，无库存概念，不撮合。

### 5.2 费用与资金

- 新账户初始资金：100,000.00 元。
- 佣金：双边收取，`max(成交金额 × 0.0003, 5)` 元。
- 印花税：仅卖出收取，`成交金额 × 0.001`。
- 买入总支出 = 成交金额 + 佣金；卖出净收入 = 成交金额 − 佣金 − 印花税。
- 成本均价（买入后）：`new_avg_cost = (old_shares × old_avg + 成交金额 + 佣金) / new_shares`；卖出不改变 avg_cost。
- 资产看板：可用现金 = balance；股票市值 = Σ(currentPrice × shares)；总资产 = 现金 + 市值；总盈亏 = 总资产 − 100,000；浮动盈亏 = Σ((currentPrice − avg_cost) × shares)；卖出实现盈亏在流水查询时计算。

### 5.3 下单校验链（按顺序短路）

1. 已认证；
2. 市场处于开盘状态（否则返回"休市中，无法下单"）；
3. 数量为 100 的正整数倍；
4. 股票存在且属于本账户游戏世界；
5. 买入：currentPrice < limitUp（涨停拒买）；卖出：currentPrice > limitDown（跌停拒卖）；
6. 买入：balance ≥ 成交金额 + 佣金；卖出：available_shares ≥ 委托数量；
7. 执行事务。

## 6. 突发事件与内幕消息

- **触发**：开盘期间，每完成 20~40 跳掷一次骰，按概率触发 1 起事件：随机选取本账户世界中的 1 只股票 + 随机事件模板（利好 / 利空）。事件模板硬编码于代码（枚举或 JSON 资源），不建模板表。
- **名义幅度**：事件模板标注冲击幅度（如 ±20%~60%），但受当日 ±10% 涨跌停板夹逼——"闪崩 50%"的实际表现为**瞬间封死跌停板**，并在随后若干跳注入同向漂移，重大事件可连续多日跌停（与 A 股真实表现一致）。
- **内幕消息**：事件爆发前 10~20 跳（30~60 秒），以 70% 概率向**持有该股票**的玩家推送一条传闻消息（未持仓则不推送）；消息落 `user_message` 表。
- **事件爆发**：施加价格冲击与后续漂移 → 向该用户推送 `/user/queue/events` 公告 → 写 `event_record` 与 `user_message`。
- 午休与收盘状态不触发事件。

## 7. 并发控制（核心技术指标）

- **悲观锁（交易主路径）**：交易方法 `@Transactional(rollbackFor = Exception.class)`，事务内：
  1. `SELECT ... FOR UPDATE` 锁定 `sys_user` 账户行；
  2. 再 `SELECT ... FOR UPDATE` 锁定该股票的 `user_position` 行（不存在则插入，重复插入依赖唯一索引兜底后重查加锁）；
  3. 固定加锁顺序：先账户行、后持仓行，防止多事务死锁；
  4. 扣减/增加 balance、更新持仓、写 trade_record，同事务提交。
- **乐观锁（行情快照）**：`user_stock_state.version`，快照更新失败重试至多 3 次。
- **并发场景**：同一账户多标签页 / 双击 / 脚本重放导致的瞬间并发下单；行情线程与交易线程并发读写。系统不依赖"多玩家抢同一股票"场景（账户世界隔离）。
- 辅助手段：前端按钮点击后置灰防连点；REST 层做基础参数校验。

## 8. WebSocket 设计

- 端点：`/ws`，STOMP 子协议；CONNECT 帧携带 JWT（查询参数 token），服务端 ChannelInterceptor 鉴权并建立 Principal（用户名）。
- 全部为用户私有目的地（点对点，无全局广播）：

| 目的地 | 内容 | 时机 |
|---|---|---|
| `/user/queue/quotes` | 本账户 8 只股票最新行情（价格、涨跌幅、高低、量无） | 每 3 秒（开盘中） |
| `/user/queue/market-state` | 市场状态变更（开盘/午休/收盘/结算完成）、倒计时同步 | 状态切换时 |
| `/user/queue/events` | 突发事件公告 | 事件爆发时 |
| `/user/queue/tips` | 内幕传闻消息 | 事件爆发前 10~20 跳 |
| `/user/queue/trade-result` | 成交回报（辅助，下单本身 HTTP 同步返回） | 成交后 |

## 9. 数据模型

所有金额使用 `DECIMAL(12,2)`，价格 `DECIMAL(10,2)`。

1. **sys_user**：id、username（唯一索引）、password（BCrypt）、balance、created_at。
2. **stock_info**（静态档案，全服共享）：id、stock_code（唯一）、stock_name、base_price（发行价）。初始化 8 只玩梗命名股票，发行价 10~100 元。
3. **user_stock_state**（每账户每只股票的行情状态）：id、user_id、stock_id、prev_close、open_price、high_price、low_price、current_price、version（乐观锁）；唯一索引 (user_id, stock_id)。
4. **user_position**：id、user_id、stock_id、shares（总股数）、available_shares（可卖股数，T+1）、avg_cost；唯一索引 (user_id, stock_id)。
5. **trade_record**：id、user_id、stock_id、trade_type（BUY/SELL）、price、shares、amount（成交金额）、commission、tax、game_day、created_at。
6. **event_record**：id、user_id、stock_id、event_type（GOOD/BAD）、title、content、impact_pct、game_day、created_at。
7. **user_message**（消息中心：公告 / 内幕 / 系统通知）：id、user_id、type（TIP/EVENT/SYSTEM）、title、content、stock_id（可空）、is_read、game_day、created_at。
8. **user_game_state**（单行/账户）：id、user_id（唯一）、current_day、market_state（NOT_STARTED/MORNING_OPEN/LUNCH_BREAK/AFTERNOON_OPEN/SETTLING/CLOSED）、tick_index（当日已推进跳数，用于恢复）、updated_at。

**注册事务内自动播种**：创建 sys_user（balance=100,000）+ user_game_state（current_day=1, market_state=NOT_STARTED, tick_index=0）+ 8 行 user_stock_state（current/open/high/low/prev_close 均 = base_price）。

## 10. REST API 概览

- `POST /api/auth/register`、`POST /api/auth/login`（返回 JWT）
- `GET  /api/me/assets`：现金、市值、总资产、总盈亏
- `GET  /api/me/positions`：持仓列表（含可卖股数、成本、浮盈）
- `GET  /api/me/trades`：交易流水分页
- `GET  /api/stocks`：8 只股票静态信息 + 本账户最新行情
- `GET  /api/stocks/{id}/quotes`：当日分时点序列（内存环形缓冲）
- `POST /api/trade/buy` `{stockId, shares}`、`POST /api/trade/sell` `{stockId, shares}`
- `GET  /api/market/state`：本账户 market_state、current_day、tick_index、阶段倒计时
- `POST /api/market/start`：开始第 1 天 / 下一交易日（幂等）
- `GET  /api/messages`、`PUT /api/messages/{id}/read`：消息中心

## 11. 前端页面（Vue 3 + Element Plus）

纵向菜单布局（左侧纵向导航）：

1. **登录 / 注册页**。
2. **大盘页**：顶部市场状态条（第 N 个交易日、上午盘/午休/下午盘/已收盘、阶段倒计时、"开始 / 下一交易日"按钮——仅 NOT_STARTED/CLOSED 可用）；8 只股票行情表（代码、名称、现价、涨跌幅、今开/最高/最低、涨停/跌停价），点击行进入个股详情；右上角消息铃铛（未读数，内幕消息红色醒目）。
3. **个股详情页**：ECharts 分时折线图；买卖面板（数量输入、整手校验、预估金额/佣金/印花税、买入/卖出按钮——涨停时买入禁用、跌停时卖出禁用，休市时整体禁用并提示）；该股票持仓摘要。
4. **资产持仓页**：资产卡片（现金、市值、总资产、总盈亏）、持仓表、交易流水表（分页）。
5. **消息中心页**：公告 / 内幕 / 系统消息列表，已读/未读状态。

WebSocket 连接在登录后建立，断线自动重连。

## 12. 错误处理

- 业务异常统一 `BusinessException` + `@RestControllerAdvice` 返回标准错误码与文案。
- 明确文案场景：未登录 / token 过期、休市中拒单、非整手、涨停拒买、跌停拒卖、资金不足、可卖股数不足、股票不存在、重复开盘（幂等返回当前状态）。
- WebSocket 推送失败不影响交易主流程；消息推送失败仅记录日志。

## 13. 测试策略

- **单元测试**：佣金/印花税计算、涨跌停价计算与价格夹逼、整手校验、T+1 解冻、随机游走边界、结算流程。
- **集成测试**（本机 MySQL test 库）：注册播种、完整交易买卖流程、市场状态机推进、事件与内幕消息落库。
- **并发压测**：同一 JWT 100 线程并发买入同一股票，断言 balance ≥ 0、持仓股数与流水金额守恒、无死锁（事务在超时内完成）；行情线程同时持续推进。
- **前端**：手工验证清单（登录→开盘→看行情→买卖→等收盘结算→次日 T+1 卖出→收内幕消息）。

## 14. 本期明确不做（YAGNI）

Redis、消息队列、多日 K 线历史持久化、玩家挂单与订单簿撮合、股票库存/超卖模型、主力/庄家玩家角色、股票停牌、行情全局共享、找回密码与个人资料、管理后台、Docker 部署编排。以上均为未来扩展项。
