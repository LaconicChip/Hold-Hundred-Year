package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日终结算服务（spec §3 SETTLING→CLOSED）。完整 12 步结算（快照/指数收盘/连板与 ST
 * 演进/隔夜 gap 掷点等）由 Phase 5.4 实现；本骨架仅做状态落位：
 * 第 108 个交易日结算后 FINISHED/NORMAL_FINISH，否则 CLOSED 等待下一日开盘。
 */
@Service
public class SettlementService {

    private final UserGameStateMapper userGameStateMapper;

    public SettlementService(UserGameStateMapper userGameStateMapper) {
        this.userGameStateMapper = userGameStateMapper;
    }

    /**
     * 结算指定用户的当前交易日。调用前 market_state 应已由 WorldTickService 经
     * toSettling 转入 SETTLING（spec §3：45 跳收盘或熔断提前收盘）；骨架不做状态
     * 校验，仅落位终态（SETTLING → CLOSED / FINISHED）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void settle(long userId) {
        UserGameState gs = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gs == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (gs.getCurrentDay() >= GameConstants.TOTAL_DAYS) {
            gs.setMarketState(MarketState.FINISHED.name());
            gs.setEndReason("NORMAL_FINISH");
            gs.setEndDay(gs.getCurrentDay());
        } else {
            gs.setMarketState(MarketState.CLOSED.name());
        }
        userGameStateMapper.updateById(gs);
    }
}
