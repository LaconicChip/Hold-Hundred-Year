package com.holdhundredyear.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.holdhundredyear.market.entity.UserStockState;
import org.apache.ibatis.annotations.Insert;

public interface UserStockStateMapper extends BaseMapper<UserStockState> {

    /**
     * 幂等补行：老存档升级后要补 3 只 ETF 的行情行（2026-10-25 新增 ETF 时没有迁移脚本）。
     * 唯一键 (user_id, stock_id) 兜底，重复插会被 IGNORE 掉。
     */
    @Insert("INSERT IGNORE INTO user_stock_state "
            + "(user_id, stock_id, prev_close, open_price, high_price, low_price, current_price, "
            + "limit_up_streak, halted, is_st, gap_pct, version) "
            + "VALUES (#{userId}, #{stockId}, #{prevClose}, #{openPrice}, #{highPrice}, #{lowPrice}, "
            + "#{currentPrice}, 0, 0, 0, 0, 0)")
    int insertIgnore(UserStockState state);
}
