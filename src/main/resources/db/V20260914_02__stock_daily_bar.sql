-- 迁移 2026-09-14：日K落库
-- 新表 stock_daily_bar：每账户每股票每交易日的 OHLC 日线（结算时落库，历史不丢），
-- 支撑个股页日K蜡烛图（GET /api/stocks/{id}/kline）。
-- 适用：已存在的 hold_year / hold_year_test 库；新库直接用 schema.sql 无需执行本文件。

CREATE TABLE IF NOT EXISTS stock_daily_bar (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    game_day INT NOT NULL,
    open DECIMAL(10,2) NOT NULL,
    high DECIMAL(10,2) NOT NULL,
    low DECIMAL(10,2) NOT NULL,
    close DECIMAL(10,2) NOT NULL,
    change_pct DOUBLE NULL,
    UNIQUE KEY uk_user_stock_day (user_id, stock_id, game_day),
    INDEX idx_user_stock (user_id, stock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
