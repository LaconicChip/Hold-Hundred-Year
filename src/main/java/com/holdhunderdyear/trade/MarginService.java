package com.holdhunderdyear.trade;

import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.event.NotifyPublisher;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.model.dto.LoanResultDTO;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.market.QuoteBoardRegistry;
import com.holdhunderdyear.market.entity.LoanRecord;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.TradeRecord;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserPosition;
import com.holdhunderdyear.market.mapper.LoanRecordMapper;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.TradeRecordMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserPositionMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 融资服务（Phase 7，spec §6.4）：借款/还款入口 + 追保/强平/爆仓核查核心。
 * 借款/还款在世界锁 + TransactionTemplate 锁内提交（模式同 TradeService），再取
 * 行情板锁保护现价读取；市场态宽口径——休市/午休/结算中均可借还，仅终态
 * （FINISHED/BANKRUPT）拒绝（终态口径同 TradeService）。额度规则：累计负债
 * 不得超过净资产（融资上限 = 自有资金 1 倍），借/还各落一条 loan_record。
 *
 * <p>核查核心 marginCheck：担保比例 = (现金 + 全部持仓市值)/负债，负债为 0 直接
 * 跳过。比例 &lt; MARGIN_CALL_RATIO(1.4) 只推追保通知（同日去重）；&lt;
 * LIQUIDATION_RATIO(1.2) 强平——持仓按市值从大到小卖出（跳过停牌/跌停封死/
 * 可卖股数为 0 的股票，成交价取当前价，费用口径同 TradeService 卖出），净额入账
 * 后立即按 min(现金, 负债) 还款并各落一条 SELL 成交记录与 LIQUIDATE 融资流水，
 * 每卖一笔重算比例达到 LIQUIDATION_TARGET(1.3) 即止；可卖全尽仍低于目标不追加，
 * 留待下一核查时点。强平后净资产 ≤ 0 判定爆仓：状态置 BANKRUPT、
 * endReason=BANKRUPT、endDay=当日并推天台通知。
 *
 * <p>两个核查入口均不加锁不开事务（调用方 SettlementService.settle 收盘结算、
 * WorldTickService.doTickOne 盘中一跳已持有用户世界锁并在事务内）：settleCheck
 * 由收盘方传入已加载的账户/持仓/行情板；intradayCheck 自行查账并做负债为 0 的
 * 快速短路（每跳调用控制开销，不消费随机数以保 P6/P9 确定性）。爆仓时
 * intradayCheck 自行落 game_state（调用方随即短路返回），settleCheck 不落
 * （收盘方负责快照与短路）。
 */
@Service
public class MarginService {

    private static final BigDecimal COMMISSION_RATE = BigDecimal.valueOf(GameConstants.COMMISSION_RATE);
    private static final BigDecimal COMMISSION_MIN =
            BigDecimal.valueOf(GameConstants.COMMISSION_MIN).setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal STAMP_TAX_RATE = BigDecimal.valueOf(GameConstants.STAMP_TAX_RATE);
    private static final BigDecimal BORROW_LIMIT = BigDecimal.valueOf(GameConstants.BORROW_LIMIT_RATIO);
    private static final BigDecimal MARGIN_CALL = BigDecimal.valueOf(GameConstants.MARGIN_CALL_RATIO);
    private static final BigDecimal LIQUIDATION = BigDecimal.valueOf(GameConstants.LIQUIDATION_RATIO);
    private static final BigDecimal LIQUIDATION_TARGET = BigDecimal.valueOf(GameConstants.LIQUIDATION_TARGET);

    private static final String LOAN_TYPE_BORROW = "BORROW";
    private static final String LOAN_TYPE_REPAY = "REPAY";
    private static final String LOAN_TYPE_LIQUIDATE = "LIQUIDATE";

    private final QuoteBoardRegistry quoteBoardRegistry;
    private final SysUserMapper sysUserMapper;
    private final UserGameStateMapper userGameStateMapper;
    private final UserPositionMapper userPositionMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final NotifyPublisher notifyPublisher;
    private final UserMessageMapper userMessageMapper;
    private final TransactionTemplate transactionTemplate;

    public MarginService(QuoteBoardRegistry quoteBoardRegistry,
                         SysUserMapper sysUserMapper,
                         UserGameStateMapper userGameStateMapper,
                         UserPositionMapper userPositionMapper,
                         TradeRecordMapper tradeRecordMapper,
                         LoanRecordMapper loanRecordMapper,
                         NotifyPublisher notifyPublisher,
                         UserMessageMapper userMessageMapper,
                         PlatformTransactionManager transactionManager) {
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.sysUserMapper = sysUserMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.userPositionMapper = userPositionMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.loanRecordMapper = loanRecordMapper;
        this.notifyPublisher = notifyPublisher;
        this.userMessageMapper = userMessageMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 融资借款：累计负债不得超过净资产（净资产 = 现金 + 持仓市值 − 负债），通过后
     * 现金与负债同额增加并落 BORROW 流水。返回操作后余额/负债/剩余可借额度。
     */
    public LoanResultDTO borrow(long userId, BigDecimal amount) {
        return execute(userId, requireAmount(amount), true);
    }

    /**
     * 偿还融资负债：金额不得超过当前负债与现金（先校负债后校现金），落 REPAY
     * 流水。返回操作后余额/负债/剩余可借额度。
     */
    public LoanResultDTO repay(long userId, BigDecimal amount) {
        return execute(userId, requireAmount(amount), false);
    }

    /**
     * 收盘核查入口（SettlementService.settle 调用，已在世界锁 + 事务内）：复用收盘方
     * 加载的账户/持仓/行情板执行追保/强平/爆仓核查，返回是否爆仓。不落 game_state
     * ——收盘方据返回值做快照与短路。
     */
    public boolean settleCheck(long userId, UserGameState gs, SysUser user,
                               List<UserPosition> positions, QuoteBoard board) {
        return marginCheck(userId, gs.getCurrentDay(), gs, user, positions, board);
    }

    /**
     * 盘中核查入口（WorldTickService.doTickOne 调用，已在世界锁 + 行情板锁 + 事务内）：
     * 自行查账户，负债为 0 直接短路（每跳调用的开销控制）；返回是否爆仓，爆仓时
     * 落 game_state（marketState=BANKRUPT、endReason、endDay），调用方随即短路。
     */
    public boolean intradayCheck(long userId, UserGameState gs, QuoteBoard board) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        BigDecimal debt = debtOf(user);
        if (debt.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        boolean bankrupt = marginCheck(userId, gs.getCurrentDay(), gs, user, positions(userId), board);
        if (bankrupt) {
            userGameStateMapper.updateById(gs);
        }
        return bankrupt;
    }

    private LoanResultDTO execute(long userId, BigDecimal amount, boolean isBorrow) {
        quoteBoardRegistry.worldLock(userId).lock();
        try {
            return transactionTemplate.execute(status ->
                    isBorrow ? doBorrow(userId, amount) : doRepay(userId, amount));
        } finally {
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }

    private LoanResultDTO doBorrow(long userId, BigDecimal amount) {
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        ReentrantLock lock = board.getLock();
        lock.lock();
        try {
            UserGameState gs = gameState(userId);
            requireLoanState(gs);
            SysUser user = lockUser(userId);
            BigDecimal marketValue = marketValueOf(positions(userId), board);
            BigDecimal debt = debtOf(user);
            BigDecimal assets = user.getBalance().add(marketValue);
            // 额度：累计负债（debt + amount）≤ 净资产（assets − debt），判界用乘法规避除零
            if (debt.multiply(BORROW_LIMIT).add(amount)
                    .compareTo(assets.subtract(debt)) > 0) {
                throw new BizException(ErrorCode.MARGIN_EXCEED);
            }
            user.setBalance(user.getBalance().add(amount));
            user.setDebt(debt.add(amount));
            sysUserMapper.updateById(user);
            insertLoanRecord(userId, LOAN_TYPE_BORROW, amount, user.getDebt(), gs.getCurrentDay());
            return result(user, marketValue);
        } finally {
            lock.unlock();
        }
    }

    private LoanResultDTO doRepay(long userId, BigDecimal amount) {
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        ReentrantLock lock = board.getLock();
        lock.lock();
        try {
            UserGameState gs = gameState(userId);
            requireLoanState(gs);
            SysUser user = lockUser(userId);
            BigDecimal debt = debtOf(user);
            if (amount.compareTo(debt) > 0) {
                throw new BizException(ErrorCode.REPAY_EXCEED);
            }
            if (amount.compareTo(user.getBalance()) > 0) {
                throw new BizException(ErrorCode.INSUFFICIENT_CASH);
            }
            user.setBalance(user.getBalance().subtract(amount));
            user.setDebt(debt.subtract(amount));
            sysUserMapper.updateById(user);
            insertLoanRecord(userId, LOAN_TYPE_REPAY, amount, user.getDebt(), gs.getCurrentDay());
            return result(user, marketValueOf(positions(userId), board));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 核查核心（调用方锁+事务内执行）：追保通知 → 强平 → 爆仓判定。账户/持仓对象
     * 原地修改（现金/负债/持股），调用方随后以内存值为准重算口径。
     */
    private boolean marginCheck(long userId, int day, UserGameState gs, SysUser user,
                                List<UserPosition> positions, QuoteBoard board) {
        BigDecimal debt = debtOf(user);
        if (debt.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal assets = user.getBalance().add(marketValueOf(positions, board));
        if (assets.compareTo(debt.multiply(MARGIN_CALL)) < 0) {
            notifyMarginCallOnce(userId, day);
        }
        boolean liquidated = false;
        if (assets.compareTo(debt.multiply(LIQUIDATION)) < 0) {
            liquidated = liquidate(userId, day, user, positions, board);
        }
        debt = debtOf(user);
        BigDecimal netAsset = user.getBalance().add(marketValueOf(positions, board)).subtract(debt);
        if (netAsset.compareTo(BigDecimal.ZERO) <= 0) {
            gs.setMarketState(MarketState.BANKRUPT.name());
            gs.setEndReason("BANKRUPT");
            gs.setEndDay(day);
            notify(userId, day, "爆仓",
                    "强制平仓后仍资不抵债，本局宣告爆仓。您坚持了 " + day + " 个交易日，"
                            + "营业部天台风大，排队请趁早。", null);
            return true;
        }
        if (liquidated) {
            notify(userId, day, "强制平仓",
                    "维持担保比例跌破 " + percentText(GameConstants.LIQUIDATION_RATIO)
                            + "%，已按市值从大到小强制卖出部分持仓并偿还负债。", null);
        }
        return false;
    }

    /**
     * 强平执行：持仓按市值从大到小逐笔卖出（停牌/跌停封死/可卖 0 跳过），每笔净额
     * 入账后立即还款 min(现金, 负债)，比例达 LIQUIDATION_TARGET 即止。返回是否有成交。
     */
    private boolean liquidate(long userId, int day, SysUser user,
                              List<UserPosition> positions, QuoteBoard board) {
        List<UserPosition> targets = positions.stream()
                .filter(position -> position.getShares() > 0)
                .sorted(Comparator.comparing((UserPosition position) ->
                                BigDecimal.valueOf(board.quote(position.getStockId()).getCurrent())
                                        .multiply(BigDecimal.valueOf(position.getShares())))
                        .reversed())
                .toList();
        boolean sold = false;
        for (UserPosition position : targets) {
            BigDecimal debt = debtOf(user);
            if (debt.compareTo(BigDecimal.ZERO) <= 0
                    || user.getBalance().add(marketValueOf(positions, board))
                            .compareTo(debt.multiply(LIQUIDATION_TARGET)) >= 0) {
                break;
            }
            Quote quote = board.quote(position.getStockId());
            if (quote.isHalted() || !quote.canSell() || position.getAvailableShares() <= 0) {
                continue;
            }
            int shares = Math.min(position.getShares(), position.getAvailableShares());
            BigDecimal price = BigDecimal.valueOf(quote.getCurrent()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(shares));
            BigDecimal commission = commissionOf(amount);
            BigDecimal tax = amount.multiply(STAMP_TAX_RATE).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = amount.subtract(commission).subtract(tax);
            // 卖出净额入账后立即还款：repaid = min(现金, 负债)，严禁复用 TradeService（会重复加锁/开事务）
            user.setBalance(user.getBalance().add(net));
            BigDecimal repaid = user.getBalance().min(debt);
            user.setBalance(user.getBalance().subtract(repaid));
            user.setDebt(debt.subtract(repaid));
            sysUserMapper.updateById(user);

            int remaining = position.getShares() - shares;
            if (remaining == 0) {
                userPositionMapper.deleteById(position.getId());
                position.setShares(0);
                position.setAvailableShares(0);
            } else {
                position.setShares(remaining);
                position.setAvailableShares(position.getAvailableShares() - shares);
                userPositionMapper.updateById(position);
            }
            insertTradeRecord(userId, position.getStockId(), "SELL", price, shares,
                    amount, commission, tax, day);
            insertLoanRecord(userId, LOAN_TYPE_LIQUIDATE, repaid, user.getDebt(), day);
            sold = true;
        }
        return sold;
    }

    /** 融资门禁：任意市场态均可借还，终态优先拒绝（口径同 TradeService 终态分支）。 */
    private void requireLoanState(UserGameState gs) {
        MarketState state = MarketState.valueOf(gs.getMarketState());
        if (state == MarketState.FINISHED || state == MarketState.BANKRUPT) {
            throw new BizException(ErrorCode.FINISHED_STATE);
        }
    }

    /** 入口参数兜底（Controller 已有 @Valid，这里兜服务层直调与非法 JSON）。 */
    private BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private UserGameState gameState(long userId) {
        UserGameState gs = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gs == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gs;
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

    private List<UserPosition> positions(long userId) {
        return userPositionMapper.selectList(
                new LambdaQueryWrapper<UserPosition>().eq(UserPosition::getUserId, userId));
    }

    /** 总市值：现价 × 持股数求和（全部持股含 T+1 冻结，口径同 SettlementService.marketValue）。 */
    private BigDecimal marketValueOf(List<UserPosition> positions, QuoteBoard board) {
        BigDecimal marketValue = BigDecimal.ZERO;
        for (UserPosition position : positions) {
            marketValue = marketValue.add(BigDecimal.valueOf(board.quote(position.getStockId()).getCurrent())
                    .multiply(BigDecimal.valueOf(position.getShares())));
        }
        return marketValue;
    }

    /** 负债空值归零（schema 允许 null，未融资用户）。 */
    private BigDecimal debtOf(SysUser user) {
        return user.getDebt() != null ? user.getDebt() : BigDecimal.ZERO;
    }

    /** 佣金：成交金额 × 万 3，单笔最低 5 元（口径同 TradeService）。 */
    private BigDecimal commissionOf(BigDecimal amount) {
        BigDecimal commission = amount.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
        return commission.max(COMMISSION_MIN);
    }

    /** 追保通知同日去重：同一天同标题已推过则静默（spec 每日核查口径）。 */
    private void notifyMarginCallOnce(long userId, int day) {
        Long count = userMessageMapper.selectCount(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getTitle, "追保通知")
                .eq(UserMessage::getGameDay, day));
        if (count != null && count > 0) {
            return;
        }
        notify(userId, day, "追保通知",
                "维持担保比例已低于 " + percentText(GameConstants.MARGIN_CALL_RATIO)
                        + "%，请补充担保物或偿还负债。", null);
    }

    /** 系统通知落库（字段惯例统一在 {@link NotifyPublisher}）。 */
    private void notify(long userId, int day, String title, String content, Long stockId) {
        notifyPublisher.system(userId, day, title, content, stockId);
    }

    /** 比例转百分比文本（1.4 → "140"），文案由常量派生（同 SettlementService）。 */
    private static String percentText(double ratio) {
        return BigDecimal.valueOf(ratio).multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros().toPlainString();
    }

    private void insertLoanRecord(long userId, String loanType, BigDecimal amount, BigDecimal debtAfter, int gameDay) {
        LoanRecord record = new LoanRecord();
        record.setUserId(userId);
        record.setLoanType(loanType);
        record.setAmount(amount);
        record.setDebtAfter(debtAfter);
        record.setGameDay(gameDay);
        loanRecordMapper.insert(record);
    }

    private void insertTradeRecord(long userId, long stockId, String tradeType, BigDecimal price, int shares,
                                   BigDecimal amount, BigDecimal commission, BigDecimal tax, int gameDay) {
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
        tradeRecordMapper.insert(record);
    }

    private LoanResultDTO result(SysUser user, BigDecimal marketValue) {
        BigDecimal debt = debtOf(user);
        LoanResultDTO result = new LoanResultDTO();
        result.setBalance(user.getBalance());
        result.setDebt(debt);
        result.setBorrowable(user.getBalance().add(marketValue)
                .subtract(debt).subtract(debt.multiply(BORROW_LIMIT)));
        return result;
    }
}
