-- 迁移 2026-10-10：新增"高利贷"板块（场外配资，风险自担）
-- 背景：融资负债被"累计负债 ≤ 净资产"锁死在 2 倍仓位，连续跌停链最多 3 天，
-- 强平总能在资不抵债前把人救回来，天台结局实际打不出来。高利贷给玩家一条主动选择的
-- 自毁通道：可借 4 倍净资产、日息按账龄递增、到期必须还，卖不掉就是爆仓。
-- 口径：高利贷不计入维持担保比例（券商只平自己的仓），但计入净资产与资不抵债判定；
-- 所以融资债与高利贷余额分列两栏，担保比例只读 debt，净资产读 debt + shark_debt。
-- 回填：存量账户 shark_debt = 0（没有高利贷行，行为与升级前完全相同）。
-- 适用：已存在的 hold_year / hold_year_test 库；新库直接用 schema.sql 无需执行本文件。

ALTER TABLE sys_user ADD COLUMN shark_debt DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER debt;

ALTER TABLE user_daily_snapshot ADD COLUMN shark_debt DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER debt;

CREATE TABLE IF NOT EXISTS user_shark_loan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    principal DECIMAL(12,2) NOT NULL,
    balance DECIMAL(12,2) NOT NULL,
    start_day INT NOT NULL,
    due_day INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
