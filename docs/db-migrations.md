# 数据库迁移说明（手工执行）

`src/main/resources/db/schema.sql` 只对新库生效（`CREATE TABLE IF NOT EXISTS`），
已存在的库需要手工执行对应迁移脚本。脚本位于 `src/main/resources/db/`，按文件名序号顺序执行：

| 脚本 | 内容 | 关联改动 |
| --- | --- | --- |
| `V20260914_01__user_stock_state_day_change_pct.sql` | `user_stock_state` 加列 `day_change_pct DOUBLE NULL` | 收盘涨跌幅持久化（收盘后行情表涨跌幅不再恒为 0） |
| `V20260914_02__stock_daily_bar.sql` | 新建表 `stock_daily_bar`（每账户每股票每交易日的 OHLC 日线） | 日K落库与 `GET /api/stocks/{id}/kline` |

## 执行方式

```bash
mysql -uroot -p hold_year      < src/main/resources/db/V20260914_01__user_stock_state_day_change_pct.sql
mysql -uroot -p hold_year      < src/main/resources/db/V20260914_02__stock_daily_bar.sql
# 测试库同理（hold_year_test）
```

## 说明

- `day_change_pct` 为可空列：盘中为 NULL，收盘结算时写入当日涨跌幅（比率口径，`0.04` = +4%）。
  该列存在的意义是：结算会把 `prev_close` 双写为当日收盘价（为次日涨跌停基准准备），
  此后实时涨跌幅 `(current - prev_close) / prev_close` 恒为 0，收盘态展示改用本列。
- `stock_daily_bar` 在收盘结算时、重置当日 OHL **之前**写入；停牌股同样落一行（价格冻结、涨跌幅 0）。
  快进（`POST /api/market/skip`）与正常逐跳推进走同一结算路径，历史不会因快进或缺席而缺失。
- 旧库升级后，历史日期的 `day_change_pct` 与 `stock_daily_bar` 为空（迁移前的数据无法追溯），
  新结算的交易日才产生数据；前端对无数据情形按空态展示（个股页日K空态用吉祥物提示）。
