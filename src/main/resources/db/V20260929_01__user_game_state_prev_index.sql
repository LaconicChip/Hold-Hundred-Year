-- 迁移 2026-09-29：指数昨收基准独立成列
-- 问题：index_value 单列两用（既是"昨收指数"又是"当前指数快照"），盘中每跳 checkpoint 都会把它
-- 写成盘中值。于是熔断日盘中重启进程后，行情板恢复出的 prevIndex 退化为最近一次盘中值，
-- 熔断阈值基数（WorldTickService 的 indexValue/prevIndex-1 ≤ -7%）与弹幕触发随之偏移。
-- 方案：加列 prev_index 专放"当日开盘基准指数"，开盘管线落库、盘中重启只读它。
-- 回填：先按 index_value 赋初值（等价于升级前的退化口径），下一次开盘即自动纠正为真昨收。
-- 适用：已存在的 hold_year / hold_year_test 库；新库直接用 schema.sql 无需执行本文件。

ALTER TABLE user_game_state ADD COLUMN prev_index DOUBLE NOT NULL DEFAULT 1000 AFTER index_value;

UPDATE user_game_state SET prev_index = index_value;
