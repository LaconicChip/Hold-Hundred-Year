package com.holdhundredyear.event;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;

/**
 * 停牌补全（Task 8.5）：供"制裁闪崩 → 当日跌停封死 → 停牌 1~3 天 → 复牌补跌"连环链的停牌环节。
 * 只补停牌缺口，不复刻复牌补跌（已有 MarketStateService.resumeHaltedStocks）与连板停牌
 * （已有 SettlementService 三连板 &gt;= 3 → halt_until_day=day+2）。
 *
 * <p>{@link #haltForDays} 落库 user_stock_state.halted=true、halt_until_day=currentDay+days
 * 并在行情板把对应 quote 置 halted；此后 tickOne 自动冻结该股价格、openDay 到 halt_until_day
 * 自动执行复牌补跌，无需本类再做。
 */
@Service
public class HaltService {

    private final UserStockStateMapper userStockStateMapper;

    public HaltService(UserStockStateMapper userStockStateMapper) {
        this.userStockStateMapper = userStockStateMapper;
    }

    /** 停牌 days 天：行情板置 halted + stock_state 落 halted、halt_until_day=currentDay+days，
     *  current_price 不动（保持停牌时价）。 */
    public void haltForDays(long userId, QuoteBoard board, long stockId, int currentDay, int days) {
        board.quote(stockId).setHalted(true);
        userStockStateMapper.update(null, new UpdateWrapper<UserStockState>()
                .eq("user_id", userId)
                .eq("stock_id", stockId)
                .set("halted", true)
                .set("halt_until_day", currentDay + days));
    }
}