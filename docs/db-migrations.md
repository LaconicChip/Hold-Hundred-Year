# 数据库迁移说明（手工执行）

`src/main/resources/db/schema.sql` 只对新库生效（`CREATE TABLE IF NOT EXISTS`），
已存在的库需要手工执行对应迁移脚本。脚本位于 `src/main/resources/db/`，按文件名序号顺序执行：

| 脚本 | 内容 | 关联改动 |
| --- | --- | --- |
| `V20260914_01__user_stock_state_day_change_pct.sql` | `user_stock_state` 加列 `day_change_pct DOUBLE NULL` | 收盘涨跌幅持久化（收盘后行情表涨跌幅不再恒为 0） |
| `V20260914_02__stock_daily_bar.sql` | 新建表 `stock_daily_bar`（每账户每股票每交易日的 OHLC 日线） | 日K落库与 `GET /api/stocks/{id}/kline` |
| `V20260929_01__user_game_state_prev_index.sql` | `user_game_state` 加列 `prev_index DOUBLE NOT NULL DEFAULT 1000` 并按 `index_value` 回填 | 指数昨收基准独立成列（熔断日盘中重启不再把阈值基数当成盘中值） |

## 执行方式

```bash
mysql -uroot -p hold_year      < src/main/resources/db/V20260914_01__user_stock_state_day_change_pct.sql
mysql -uroot -p hold_year      < src/main/resources/db/V20260914_02__stock_daily_bar.sql
mysql -uroot -p hold_year      < src/main/resources/db/V20260929_01__user_game_state_prev_index.sql
# 测试库同理（hold_year_test）
```

## 说明

- `day_change_pct` 为可空列：盘中为 NULL，收盘结算时写入当日涨跌幅（比率口径，`0.04` = +4%）。
  该列存在的意义是：结算会把 `prev_close` 双写为当日收盘价（为次日涨跌停基准准备），
  此后实时涨跌幅 `(current - prev_close) / prev_close` 恒为 0，收盘态展示改用本列。
- `prev_index` 是"当日开盘基准指数"（昨收口径）：开盘管线写入、盘中只读。
  此前 `index_value` 单列两用（昨收 + 当前快照），而每跳 checkpoint 都会把 `index_value` 写成盘中值，
  于是熔断日盘中重启进程后，行情板恢复出的熔断阈值基数会变成最近一次盘中值。
  迁移按 `index_value` 回填只是过渡口径（等价于升级前的行为），下一次开盘即自动纠正为真昨收，无需手工修数据。
  **未执行本迁移的库会让 `user_game_state` 相关读写报"Unknown column"**，测试库同样必须执行。
- `stock_daily_bar` 在收盘结算时、重置当日 OHL **之前**写入；停牌股同样落一行（价格冻结、涨跌幅 0）。
  快进（`POST /api/market/skip`）与正常逐跳推进走同一结算路径，历史不会因快进或缺席而缺失。
- 旧库升级后，历史日期的 `day_change_pct` 与 `stock_daily_bar` 为空（迁移前的数据无法追溯），
  新结算的交易日才产生数据；前端对无数据情形按空态展示（个股页日K空态用吉祥物提示）。
