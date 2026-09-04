-- 《你持有一百年看看》schema v1 (14 tables)

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    balance DECIMAL(12,2) NOT NULL,
    debt DECIMAL(12,2) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stock_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(10) NOT NULL UNIQUE,
    stock_name VARCHAR(50) NOT NULL,
    sector VARCHAR(20) NOT NULL,
    base_price DECIMAL(10,2) NOT NULL,
    beta DOUBLE NOT NULL,
    index_weight DOUBLE NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_stock_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    prev_close DECIMAL(10,2) NOT NULL,
    open_price DECIMAL(10,2) NULL,
    high_price DECIMAL(10,2) NULL,
    low_price DECIMAL(10,2) NULL,
    current_price DECIMAL(10,2) NOT NULL,
    limit_up_streak INT NOT NULL DEFAULT 0,
    halted TINYINT(1) NOT NULL DEFAULT 0,
    halt_until_day INT NULL,
    is_st TINYINT(1) NOT NULL DEFAULT 0,
    gap_pct DOUBLE NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_stock (user_id, stock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    shares INT NOT NULL DEFAULT 0,
    available_shares INT NOT NULL DEFAULT 0,
    avg_cost DECIMAL(10,2) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_stock (user_id, stock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trade_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    trade_type VARCHAR(8) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    shares INT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    commission DECIMAL(12,2) NOT NULL,
    tax DECIMAL(12,2) NOT NULL,
    game_day INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_day (user_id, game_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS loan_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    loan_type VARCHAR(16) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    debt_after DECIMAL(12,2) NOT NULL,
    game_day INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_day (user_id, game_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS game_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NULL,
    scope VARCHAR(16) NOT NULL,
    script_type VARCHAR(32) NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    impact_pattern VARCHAR(32) NULL,
    title VARCHAR(128) NOT NULL,
    content TEXT NULL,
    impact_pct DOUBLE NULL,
    game_day INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_day (user_id, game_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    truth_status VARCHAR(16) NULL,
    type VARCHAR(16) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content TEXT NULL,
    stock_id BIGINT NULL,
    followup_of BIGINT NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    game_day INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_day (user_id, game_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_game_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    current_day INT NOT NULL DEFAULT 1,
    market_state VARCHAR(32) NOT NULL,
    tick_index INT NOT NULL DEFAULT 0,
    world_seed BIGINT NOT NULL,
    index_value DOUBLE NOT NULL DEFAULT 1000,
    prev_total_asset DECIMAL(12,2) NULL,
    vol_regime VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    tier VARCHAR(16) NOT NULL DEFAULT 'RETAIL',
    end_reason VARCHAR(32) NULL,
    end_day INT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_title (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title_code VARCHAR(64) NOT NULL,
    title_name VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    game_day INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_title (user_id, title_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_era (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    era_index INT NOT NULL,
    era_code VARCHAR(32) NOT NULL,
    start_day INT NOT NULL,
    end_day INT NOT NULL,
    params_json TEXT NULL,
    UNIQUE KEY uk_user_era (user_id, era_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_script_day (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    game_day INT NOT NULL,
    script_type VARCHAR(32) NOT NULL,
    params_json TEXT NULL,
    UNIQUE KEY uk_user_script (user_id, game_day, script_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_daily_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    game_day INT NOT NULL,
    cash DECIMAL(12,2) NOT NULL,
    market_value DECIMAL(12,2) NOT NULL,
    debt DECIMAL(12,2) NOT NULL,
    net_asset DECIMAL(12,2) NOT NULL,
    day_return_pct DOUBLE NULL,
    index_value DOUBLE NOT NULL,
    index_day_pct DOUBLE NULL,
    npc_assets_json TEXT NULL,
    UNIQUE KEY uk_user_day (user_id, game_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_ipo_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    script_day_id BIGINT NOT NULL,
    ipo_name VARCHAR(64) NOT NULL,
    issue_price DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    profit DECIMAL(12,2) NULL,
    game_day INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_day (user_id, game_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
