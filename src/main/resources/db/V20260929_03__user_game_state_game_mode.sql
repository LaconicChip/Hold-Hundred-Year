-- 迁移 2026-09-29：新增"快速模式"，一局一个档案
-- 背景：快速模式 = 36 个交易日（12 天/年 × 3 年）、每天墙钟再减半（500ms/跳、午休 5 秒）、
-- 涨跌停抬到 ±20%（ST ±10%）。机制一个不减，只是节奏更密、单日振幅更大。
-- 模式必须"一局一定"而不是全局改常量：同种子可复现与"快进=逐跳"等价都依赖整局参数一致，
-- 所以把档案写进账户行，所有按天/按毫秒的判定都从这一列取。
-- 回填：存量账户一律 STANDARD（节奏与升级前完全相同）。
-- 适用：已存在的 hold_year / hold_year_test 库；新库直接用 schema.sql 无需执行本文件。

ALTER TABLE user_game_state ADD COLUMN game_mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD' AFTER world_seed;
