package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.model.dto.TradeDTO;
import com.holdhundredyear.game.model.dto.TradeResultDTO;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 交易服务（Phase 6）：A股买入/卖出。成交价取下单瞬间的内存现价；佣金万 3
 * 单笔最低 5 元双边收取，印花税千 1 仅卖出收取；买入只增 shares 不增
 * available_shares（T+1 冻结，收盘由 SettlementService 解冻）；卖出不改
 * avg_cost，买入按 (旧股数×旧成本 + 成交金额 + 佣金) / 新股数 加权；清仓删除
 * 持仓行。校验链按序短路：市场态 → 整手 → 股票存在且未停牌 → 涨跌停 →
 * 资金/可卖股数 → 执行。
 *
 * <p>并发协议（P5）：入口参数校验在世界锁外；全程持有用户世界锁并经
 * TransactionTemplate 锁内提交（与 tickOne/startDay 互斥，模式同
 * WorldTickService）；doBuy/doSell 内再取行情板锁保护现价读取；资金行与持仓行
 * 悲观锁 FOR UPDATE（先资金行后持仓行，全服务同序防死锁）。
 * 金额计算全程 BigDecimal、setScale(2, HALF_UP)，禁 double。
 *
 * <p>WebSocket trade-result 推送（Phase 10.2）预留接入点：doBuy/doSell
 * 末尾 return 前（事务提交前广播会话即可拿到成交明细）。
 */
@Service
public class TradeService {

    private static final BigDecimal COMMISSION_RATE = BigDecimal.valueOf(GameConstants.COMMISSION_RATE);
    private static final BigDecimal COMMISSION_MIN =
            BigDecimal.valueOf(GameConstants.COMMISSION_MIN).setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal STAMP_TAX_RATE = BigDecimal.valueOf(GameConstants.STAMP_TAX_RATE);
    private static final BigDecimal ZERO_TAX = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final QuoteBoardRegistry quoteBoardRegistry;
    private final SysUserMapper sysUserMapper;
    private final UserGameStateMapper userGameStateMapper;
    private final UserPositionMapper userPositionMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final TransactionTemplate transactionTemplate;

    public TradeService(QuoteBoardRegistry quoteBoardRegistry,
                        SysUserMapper sysUserMapper,
                        UserGameStateMapper userGameStateMapper,
                        UserPositionMapper userPositionMapper,
                        TradeRecordMapper tradeRecordMapper,
                        PlatformTransactionManager transactionManager) {
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.sysUserMapper = sysUserMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.userPositionMapper = userPositionMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 买入：整手、未停牌、未涨停封死且资金充足时按现价成交；买入股数全部冻结
     * （T+1），并落一条 BUY 成交记录。返回成交明细（price/shares/amount/
     * commission/tax/balance）。
     */
    public TradeResultDTO buy(long userId, TradeDTO dto) {
        return execute(userId, dto, true);
    }

    /**
     * 卖出：整手、未停牌、未跌停封死且可卖股数充足时按现价成交；净收入入账并
     * 落一条 SELL 成交记录，清仓删除持仓行。返回成交明细同 buy。
     */
    public TradeResultDTO sell(long userId, TradeDTO dto) {
        return execute(userId, dto, false);
    }

    private TradeResultDTO execute(long userId, TradeDTO dto, boolean isBuy) {
        requireOrder(dto);
        quoteBoardRegistry.worldLock(userId).lock();
        try {
            return transactionTemplate.execute(status ->
                    isBuy ? doBuy(userId, dto) : doSell(userId, dto));
        } finally {
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }

    private TradeResultDTO doBuy(long userId, TradeDTO dto) {
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        ReentrantLock lock = board.getLock();
        lock.lock();
        try {
            UserGameState gs = gameState(userId);
            requireTradingState(gs);
            requireLot(dto.getShares());
            Quote quote = requireTradable(board, dto.getStockId());
            if (!quote.canBuy()) {
                throw new BizException(ErrorCode.LIMIT_UP_CANNOT_BUY);
            }
            SysUser user = lockUser(userId);
            BigDecimal price = priceOf(quote);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(dto.getShares()));
            BigDecimal commission = commissionOf(amount);
            BigDecimal total = amount.add(commission);
            if (user.getBalance().compareTo(total) < 0) {
                throw new BizException(ErrorCode.INSUFFICIENT_CASH);
            }
            user.setBalance(user.getBalance().subtract(total));
            sysUserMapper.updateById(user);

            UserPosition position = lockPosition(userId, dto.getStockId());
            if (position == null) {
                position = new UserPosition();
                position.setUserId(userId);
                position.setStockId(dto.getStockId());
                position.setShares(dto.getShares());
                position.setAvailableShares(0);
                position.setAvgCost(total.divide(BigDecimal.valueOf(dto.getShares()), 2, RoundingMode.HALF_UP));
                userPositionMapper.insert(position);
            } else {
                int newShares = position.getShares() + dto.getShares();
                BigDecimal newAvgCost = position.getAvgCost()
                        .multiply(BigDecimal.valueOf(position.getShares()))
                        .add(total)
                        .divide(BigDecimal.valueOf(newShares), 2, RoundingMode.HALF_UP);
                position.setShares(newShares);
                position.setAvgCost(newAvgCost);
                userPositionMapper.updateById(position);
            }
            insertRecord(userId, dto.getStockId(), "BUY", price, dto.getShares(),
                    amount, commission, ZERO_TAX, gs.getCurrentDay(), chartTickIndex(board));
            return result(price, dto.getShares(), amount, commission, ZERO_TAX, user.getBalance());
        } finally {
            lock.unlock();
        }
    }

    private TradeResultDTO doSell(long userId, TradeDTO dto) {
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        ReentrantLock lock = board.getLock();
        lock.lock();
        try {
            UserGameState gs = gameState(userId);
            requireTradingState(gs);
            requireLot(dto.getShares());
            Quote quote = requireTradable(board, dto.getStockId());
            if (!quote.canSell()) {
                throw new BizException(ErrorCode.LIMIT_DOWN_CANNOT_SELL);
            }
            SysUser user = lockUser(userId);
            UserPosition position = lockPosition(userId, dto.getStockId());
            if (position == null || position.getAvailableShares() < dto.getShares()) {
                throw new BizException(ErrorCode.INSUFFICIENT_SHARES);
            }
            BigDecimal price = priceOf(quote);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(dto.getShares()));
            BigDecimal commission = commissionOf(amount);
            BigDecimal tax = amount.multiply(STAMP_TAX_RATE).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = amount.subtract(commission).subtract(tax);
            user.setBalance(user.getBalance().add(net));
            sysUserMapper.updateById(user);

            int remaining = position.getShares() - dto.getShares();
            if (remaining == 0) {
                userPositionMapper.deleteById(position.getId());
            } else {
                position.setShares(remaining);
                position.setAvailableShares(position.getAvailableShares() - dto.getShares());
                userPositionMapper.updateById(position);
            }
            insertRecord(userId, dto.getStockId(), "SELL", price, dto.getShares(),
                    amount, commission, tax, gs.getCurrentDay(), chartTickIndex(board));
            return result(price, dto.getShares(), amount, commission, tax, user.getBalance());
        } finally {
            lock.unlock();
        }
    }

    /** 入口参数兜底（Controller 已有 @Valid，这里兜服务层直调与非法 JSON）。 */
    private void requireOrder(TradeDTO dto) {
        if (dto == null || dto.getStockId() == null || dto.getShares() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    private UserGameState gameState(long userId) {
        UserGameState gs = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gs == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gs;
    }

    /** 市场态门禁：仅两个开盘时段可下单，终态优先于休市判定。 */
    private void requireTradingState(UserGameState gs) {
        MarketState state = MarketState.valueOf(gs.getMarketState());
        switch (state) {
            case MORNING_OPEN, AFTERNOON_OPEN -> {
            }
            case LUNCH_BREAK -> throw new BizException(ErrorCode.NOT_TRADING_TIME);
            case FINISHED, BANKRUPT -> throw new BizException(ErrorCode.FINISHED_STATE);
            default -> throw new BizException(ErrorCode.MARKET_CLOSED);
        }
    }

    private void requireLot(int shares) {
        if (shares <= 0 || shares % GameConstants.LOT_SIZE != 0) {
            throw new BizException(ErrorCode.NOT_LOT);
        }
    }

    /** 股票存在且未停牌（不查 StockDefs——行情板即世界全集）。 */
    private Quote requireTradable(QuoteBoard board, long stockId) {
        Quote quote = board.getQuotes().get(stockId);
        if (quote == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        if (quote.isHalted()) {
            throw new BizException(ErrorCode.HALTED);
        }
        return quote;
    }

    private SysUser lockUser(long userId) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .last("FOR UPDATE"));
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return user;
    }

    /** 持仓行悲观锁 FOR UPDATE：与 lockUser 组成先资金后持仓的固定锁序。 */
    private UserPosition lockPosition(long userId, long stockId) {
        return userPositionMapper.selectOne(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId)
                .eq(UserPosition::getStockId, stockId)
                .last("FOR UPDATE"));
    }

    private BigDecimal priceOf(Quote quote) {
        return BigDecimal.valueOf(quote.getCurrent()).setScale(2, RoundingMode.HALF_UP);
    }

    /** 佣金：成交金额 × 万 3，单笔最低 5 元（双边）。 */
    private BigDecimal commissionOf(BigDecimal amount) {
        BigDecimal commission = amount.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
        return commission.max(COMMISSION_MIN);
    }

    /** 成交时价格对应的是"最后一条已画出的分时点"，所以 tickIndex 要减 1；首跳前成交落在 0。 */
    private static int chartTickIndex(QuoteBoard board) {
        return Math.max(0, Math.min(GameConstants.TOTAL_TICKS - 1, board.getTickIndex() - 1));
    }

    private void insertRecord(long userId, long stockId, String tradeType, BigDecimal price, int shares,
                              BigDecimal amount, BigDecimal commission, BigDecimal tax, int gameDay,
                              int tickIndex) {
        TradeRecord record = new TradeRecord();
        record.setUserId(userId);
        record.setStockId(stockId);
        record.setTradeType(tradeType);
        record.setPrice(price);
        record.setShares(shares);
        record.setAmount(amount);
        record.setCommission(commission);
        record.setTax(tax);
        record.setGameDay(gameDay);
        record.setTickIndex(tickIndex);
        tradeRecordMapper.insert(record);
    }

    private TradeResultDTO result(BigDecimal price, int shares, BigDecimal amount,
                                  BigDecimal commission, BigDecimal tax, BigDecimal balance) {
        TradeResultDTO result = new TradeResultDTO();
        result.setPrice(price);
        result.setShares(shares);
        result.setAmount(amount);
        result.setCommission(commission);
        result.setTax(tax);
        result.setBalance(balance);
        return result;
    }
}
