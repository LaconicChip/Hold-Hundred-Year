# 数据库迁移说明（手工执行）

`src/main/resources/db/schema.sql` 只对新库生效（`CREATE TABLE IF NOT EXISTS`），
已存在的库需要手工执行对应迁移脚本。脚本位于 `src/main/resources/db/`，按文件名序号顺序执行：

| 脚本 | 内容 | 关联改动 |
| --- | --- | --- |
| `V20260914_01__user_stock_state_day_change_pct.sql` | `user_stock_state` 加列 `day_change_pct DOUBLE NULL` | 收盘涨跌幅持久化（收盘后行情表涨跌幅不再恒为 0） |
| `V20260914_02__stock_daily_bar.sql` | 新建表 `stock_daily_bar`（每账户每股票每交易日的 OHLC 日线） | 日K落库与 `GET /api/stocks/{id}/kline` |
| `V20260929_01__user_game_state_prev_index.sql` | `user_game_state` 加列 `prev_index DOUBLE NOT NULL DEFAULT 1000` 并按 `index_value` 回填 | 指数昨收基准独立成列（熔断日盘中重启不再把阈值基数当成盘中值） |
| `V20260929_02__user_message_sector.sql` | `user_message` 加列 `sector VARCHAR(16) NULL` | 板块级消息记下归属板块，个股详情页 includeSector 才能捞回本板块新闻 |
| `V20260929_03__user_game_state_game_mode.sql` | `user_game_state` 加列 `game_mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD'` | 一局一个游戏档案（标准/快速），建局选定、整局不变 |
| `V20261010_01__shark_loan.sql` | `sys_user` 与 `user_daily_snapshot` 各加列 `shark_debt DECIMAL(12,2) NOT NULL DEFAULT 0.00`；新建表 `user_shark_loan`（高利贷台账） | 高利贷板块（spec §6.6）：5 倍净资产额度、日息按账龄递增、到期强制平仓还债 |

## 执行方式

```bash
mysql -uroot -p hold_year      < src/main/resources/db/V20260914_01__user_stock_state_day_change_pct.sql
mysql -uroot -p hold_year      < src/main/resources/db/V20260914_02__stock_daily_bar.sql
mysql -uroot -p hold_year      < src/main/resources/db/V20260929_01__user_game_state_prev_index.sql
mysql -uroot -p hold_year      < src/main/resources/db/V20260929_02__user_message_sector.sql
mysql -uroot -p hold_year      < src/main/resources/db/V20260929_03__user_game_state_game_mode.sql
# 测试库同理（hold_year_test）
```

## 说明

- `shark_debt` 分列在 `sys_user`（当前未清余额）与 `user_daily_snapshot`（当日快照口径）。融资债与高利贷债必须分开记：
  维持担保比例只按 `debt` 算（券商不为民间借贷平仓），净资产与资不抵债判定按两者合计算。
  存量行回填 0，所以升级前注册的老账户不会凭空背上高利贷，行为与升级前完全相同。
- `user_shark_loan` 一笔一行（本金 / 未清余额 / 起算日 / 到期日 / 状态）。日息不落库，按账龄从常量表现算，
  改了递增规则不会留下对不上的旧行。`sys_user.shark_debt` 是这台账的汇总缓存，
  每次计息 / 还款 / 强卖后由服务端从台账重算（`SharkService.syncBalance`）。

- `day_change_pct` 为可空列：盘中为 NULL，收盘结算时写入当日涨跌幅（比率口径，`0.04` = +4%）。
  该列存在的意义是：结算会把 `prev_close` 双写为当日收盘价（为次日涨跌停基准准备），
  此后实时涨跌幅 `(current - prev_close) / prev_close` 恒为 0，收盘态展示改用本列。
- `prev_index` 是"当日开盘基准指数"（昨收口径）：开盘管线写入、盘中只读。
  此前 `index_value` 单列两用（昨收 + 当前快照），而每跳 checkpoint 都会把 `index_value` 写成盘中值，
  于是熔断日盘中重启进程后，行情板恢复出的熔断阈值基数会变成最近一次盘中值。
  迁移按 `index_value` 回填只是过渡口径（等价于升级前的行为），下一次开盘即自动纠正为真昨收，无需手工修数据。
  **未执行本迁移的库会让 `user_game_state` 相关读写报"Unknown column"**，测试库同样必须执行。
- `game_mode` 存量账户一律回填 STANDARD，节奏与升级前完全相同；`GameMode.of()`
  对 null/未知值也按标准模式兜底，所以老库与老客户端都不会因为缺字段而改变行为。
- `sector` 为可空列：只有**板块级**消息写入（值取自 `StockDefs.sector` 的 21 个板块名），
  单票消息仍用 `stock_id`、全市场消息两者皆空。个股详情页查询 `?stockId=15&includeSector=true`
  时，服务端按 `StockDefs` 反查该股板块，一次返回"本票专属 + 本票所属板块"两类消息。
  **历史存量消息无法反推板块，保持 NULL**——升级后新产生的板块消息才会出现在个股页历史区。
- `stock_daily_bar` 在收盘结算时、重置当日 OHL **之前**写入；停牌股同样落一行（价格冻结、涨跌幅 0）。
  快进（`POST /api/market/skip`）与正常逐跳推进走同一结算路径，历史不会因快进或缺席而缺失。
- 旧库升级后，历史日期的 `day_change_pct` 与 `stock_daily_bar` 为空（迁移前的数据无法追溯），
  新结算的交易日才产生数据；前端对无数据情形按空态展示（个股页日K空态用吉祥物提示）。
