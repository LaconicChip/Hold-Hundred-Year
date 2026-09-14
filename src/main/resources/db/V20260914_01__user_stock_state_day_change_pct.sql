-- 迁移 2026-09-14：收盘涨跌幅持久化
-- 问题1：结算把 prev_close 双写为当日收盘价后，current==prevClose、dayChangePct 恒为 0，
-- 收盘后行情表涨跌幅丢失。加列 day_change_pct（比率口径，0.04 = +4%），盘中为 NULL，
-- 结算时写入当日涨跌幅（(收盘-昨收)/昨收），供收盘态展示与行情板恢复（QuoteBoard.restore）。
-- 适用：已存在的 hold_year / hold_year_test 库；新库直接用 schema.sql 无需执行本文件。

ALTER TABLE user_stock_state ADD COLUMN day_change_pct DOUBLE NULL;
