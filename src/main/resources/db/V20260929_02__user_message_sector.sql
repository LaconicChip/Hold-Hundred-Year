-- 迁移 2026-09-29：板块级消息补归属板块
-- 问题：SECTOR/MARKET 级事件落库时只有 stock_id（且为 NULL），个股详情页按 stockId 过滤时
-- 看不到"自己所在板块"的新闻，历史消息区缺一半上下文。
-- 方案：加列 sector（板块名，与 StockDefs.sector 同集合），SECTOR 级事件落库时写入；
--       个股页查询支持 stockId + includeSector，一并捞回本板块消息与全市场消息所属板块。
-- 回填：历史消息无从反推板块，保持 NULL（只有升级后新产生的板块消息可被个股页捞到）。
-- 适用：已存在的 hold_year / hold_year_test 库；新库直接用 schema.sql 无需执行本文件。

ALTER TABLE user_message ADD COLUMN sector VARCHAR(16) NULL AFTER stock_id;
