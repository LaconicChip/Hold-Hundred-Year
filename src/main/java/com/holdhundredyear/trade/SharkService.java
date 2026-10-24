package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.event.NotifyPublisher;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.content.SharkLines;
import com.holdhundredyear.game.model.dto.SharkLoanDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.LoanRecord;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserSharkLoan;
import com.holdhundredyear.market.mapper.LoanRecordMapper;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.mapper.UserSharkLoanMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 高利贷（spec §6.6）：场外配资，风险自担。与营业部融资的三条根本区别——
 * <ol>
 *   <li>额度按净资产的 {@code SHARK_LIMIT_RATIO} 倍（融资是 1 倍），单独借满就是 6 倍仓位
 *       （自有 1 倍 + 借入 5 倍）；融资与高利贷是两条独立额度线，互不从对方余额直接扣减；</li>
 *   <li>日息按<b>账龄</b>逐档上调（{@code SHARK_ANNUAL_BASE} 起，每满
 *       {@code SHARK_RATE_STEP_DAYS} 个计息日加一档，封顶 {@code SHARK_ANNUAL_MAX}），
 *       融资是固定年化；利息滚进本金，越拖越贵；</li>
 *   <li>到期必须还：到期日收盘先扣现金，不足则按市值从大到小强制卖出持仓
 *       （停牌 / 跌停封死 / T+1 未解冻的跳过），卖完仍不足即资不抵债，直接爆仓。</li>
 * </ol>
 *
 * <p><b>口径</b>：高利贷<b>不计入</b>维持担保比例——券商只平自己放出去的融资仓，
 * 不替民间借贷清仓；但它<b>计入净资产与资不抵债判定</b>（{@code MarginService.marginCheck}
 * 的净资产按 融资债 + 高利贷余额 算）。两个数分列：{@code sys_user.debt} 是融资债，
 * {@code sys_user.shark_debt} 是高利贷未清余额；后者恒等于台账各笔之和，每次变动后由
 * {@link #syncBalance} 从台账重算，不做手工加减，免得两处对不上。
 *
 * <p><b>并发与确定性</b>：借 / 还走 {@code worldLock} + 行情板锁 + 锁内事务，与
 * {@code MarginService} 同一套协议；{@link #settleDay} 由收盘结算在锁内事务里调用，
 * 自身不加锁不开新事务。台词只经 {@code WorldRandom.of(worldSeed, "shark" + day)} 抽取，
 * 不消费引擎随机数，同种子同日必得同一条话，快进与逐跳等价。
 */
@Service
public class SharkService {

    /** 台账状态：在借 / 逾期未清（继续按封顶利滚）/ 已结清。 */
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_OVERDUE = "OVERDUE";
    public static final String STATUS_REPAID = "REPAID";

    /** 流水类型：与融资流水同表不同型，前端按类型打标签。 */
    static final String TYPE_BORROW = "SHARK_BORROW";
    static final String TYPE_REPAY = "SHARK_REPAY";
    static final String TYPE_INTEREST = "SHARK_INTEREST";
    static final String TYPE_LIQUIDATE = "SHARK_LIQUIDATE";

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final UserSharkLoanMapper sharkLoanMapper;
    private final UserPositionMapper userPositionMapper;
    private final SysUserMapper sysUserMapper;
    private final UserGameStateMapper userGameStateMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;
    private final NotifyPublisher notifyPublisher;
    private final TransactionTemplate transactionTemplate;

    public SharkService(UserSharkLoanMapper sharkLoanMapper, UserPositionMapper userPositionMapper,
                        SysUserMapper sysUserMapper, UserGameStateMapper userGameStateMapper,
                        TradeRecordMapper tradeRecordMapper, LoanRecordMapper loanRecordMapper,
                        QuoteBoardRegistry quoteBoardRegistry, NotifyPublisher notifyPublisher,
                        PlatformTransactionManager transactionManager) {
        this.sharkLoanMapper = sharkLoanMapper;
        this.userPositionMapper = userPositionMapper;
        this.sysUserMapper = sysUserMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.loanRecordMapper = loanRecordMapper;
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.notifyPublisher = notifyPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ---- 口径工具：净资产与额度只在这一处定义，别处一律调这里 ----

    /** 高利贷未清余额（null 安全）。 */
    public static BigDecimal sharkOf(SysUser user) {
        return user.getSharkDebt() != null ? user.getSharkDebt() : ZERO;
    }

    /** 融资债。 */
    public static BigDecimal marginOf(SysUser user) {
        return user.getDebt() != null ? user.getDebt() : ZERO;
    }

    /** 净资产 = 现金 + 持仓市值 − 融资债 − 高利贷余额（段位、曲线、额度、爆仓判定同口径）。 */
    public static BigDecimal netAssetOf(SysUser user, BigDecimal marketValue) {
        return user.getBalance().add(marketValue).subtract(marginOf(user)).subtract(sharkOf(user));
    }

    /**
     * 高利贷可借额度 = 净资产 × {@code SHARK_LIMIT_RATIO} − 已借余额。
     * 借新钱本身不改净资产（现金与余额同增），所以刚借满再问一次就是 0。
     */
    public static BigDecimal borrowableOf(SysUser user, BigDecimal marketValue) {
        BigDecimal net = netAssetOf(user, marketValue);
        if (net.signum() <= 0) {
            return ZERO;
        }
        BigDecimal room = net.multiply(BigDecimal.valueOf(GameConstants.SHARK_LIMIT_RATIO))
                .subtract(sharkOf(user));
        return room.signum() > 0 ? room.setScale(2, RoundingMode.DOWN) : ZERO;
    }

    /** 某笔借款在 day 这天的年化：起始档 + 每满一个步长加一档，封顶。 */
    public static double annualRateAt(UserSharkLoan loan, int day) {
        int age = Math.max(0, day - loan.getStartDay());
        int steps = age / GameConstants.SHARK_RATE_STEP_DAYS;
        double rate = GameConstants.SHARK_ANNUAL_BASE + (double) steps * GameConstants.SHARK_ANNUAL_STEP;
        return Math.min(rate, GameConstants.SHARK_ANNUAL_MAX);
    }

    /**
     * 日息 = 余额 × 年化 ÷ 本局年交易日数（口径与融资计息一致：快速模式一年 12 天，
     * 单日计提更多、年化不变，所以"越拖越贵"在短局里更凶）。
     */
    public static BigDecimal dailyInterestOf(BigDecimal balance, double annual, GameMode mode) {
        return balance.multiply(BigDecimal.valueOf(annual))
                .divide(BigDecimal.valueOf(mode.getDaysPerYear()), 2, RoundingMode.HALF_UP);
    }

    // ---- 对外入口：视图 / 借 / 还 ----

    /** 板块视图：余额、今日利息、到期倒计时、可借额度、逐笔台账。 */
    public SharkLoanDTO view(long userId) {
        SysUser user = requireUser(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        board.getLock().lock();
        try {
            int day = currentDay(userId);
            return toDto(userId, user, openLoans(userId), board, day, GameMode.of(gameState(userId).getGameMode()));
        } finally {
            board.getLock().unlock();
        }
    }

    /** 借高利贷：门禁 + 额度校验 → 落一笔台账（起算日 = 当日，到期日 = 当日 + 一个游戏年）→ 现金与余额同增。 */
    public SharkLoanDTO borrow(long userId, BigDecimal amount) {
        BigDecimal principal = requireAmount(amount);
        quoteBoardRegistry.worldLock(userId).lock();
        try {
            return transactionTemplate.execute(status -> doBorrow(userId, principal));
        } finally {
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }

    /** 提前还款：现金减、台账按到期日近的优先冲销；结清的笔留痕不删（流水可查）。 */
    public SharkLoanDTO repay(long userId, BigDecimal amount) {
        BigDecimal want = requireAmount(amount);
        quoteBoardRegistry.worldLock(userId).lock();
        try {
            return transactionTemplate.execute(status -> doRepay(userId, want));
        } finally {
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }

    private SharkLoanDTO doBorrow(long userId, BigDecimal amount) {
        UserGameState gs = gameState(userId);
        requireLoanState(gs);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        ReentrantLock lock = board.getLock();
        lock.lock();
        try {
            SysUser user = lockUser(userId);
            BigDecimal marketValue = marketValueOf(positions(userId), board);
            BigDecimal room = borrowableOf(user, marketValue);
            if (amount.compareTo(room) > 0) {
                throw new BizException(ErrorCode.SHARK_EXCEED);
            }
            int day = gs.getCurrentDay();
            GameMode mode = GameMode.of(gs.getGameMode());
            UserSharkLoan loan = new UserSharkLoan();
            loan.setUserId(userId);
            loan.setPrincipal(amount);
            loan.setBalance(amount);
            loan.setStartDay(day);
            loan.setDueDay(day + mode.getDaysPerYear() * GameConstants.SHARK_TERM_YEARS);
            loan.setStatus(STATUS_OPEN);
            sharkLoanMapper.insert(loan);

            user.setBalance(user.getBalance().add(amount));
            syncBalance(user, openLoans(userId));
            sysUserMapper.updateById(user);
            insertLoan(userId, TYPE_BORROW, amount, sharkOf(user), day);

            Random rng = WorldRandom.of(gs.getWorldSeed(), "shark" + day);
            notifyPublisher.system(userId, day, "高利贷到手",
                    SharkLines.pick(SharkLines.Scene.BORROWED, rng)
                            + " 到期日第 " + loan.getDueDay() + " 个交易日，到期应还约 "
                            + money(loan.getBalance().add(dailyInterestOf(loan.getBalance(),
                            GameConstants.SHARK_ANNUAL_BASE, mode))) + " 元起。");
            return toDto(userId, user, openLoans(userId), board, day, mode);
        } finally {
            lock.unlock();
        }
    }

    private SharkLoanDTO doRepay(long userId, BigDecimal amount) {
        UserGameState gs = gameState(userId);
        requireLoanState(gs);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        ReentrantLock lock = board.getLock();
        lock.lock();
        try {
            SysUser user = lockUser(userId);
            BigDecimal open = sharkOf(user);
            if (amount.compareTo(open) > 0) {
                throw new BizException(ErrorCode.REPAY_EXCEED);
            }
            if (amount.compareTo(user.getBalance()) > 0) {
                throw new BizException(ErrorCode.INSUFFICIENT_CASH);
            }
            int day = gs.getCurrentDay();
            user.setBalance(user.getBalance().subtract(amount));
            List<UserSharkLoan> loans = openLoans(userId);
            applyRepayment(loans, amount, day);
            syncBalance(user, loans);
            sysUserMapper.updateById(user);
            insertLoan(userId, TYPE_REPAY, amount, sharkOf(user), day);
            if (sharkOf(user).signum() == 0) {
                Random rng = WorldRandom.of(gs.getWorldSeed(), "shark" + day);
                notifyPublisher.system(userId, day, "高利贷结清",
                        SharkLines.pick(SharkLines.Scene.CLEARED, rng));
            }
            return toDto(userId, user, openLoans(userId), board, day, GameMode.of(gs.getGameMode()));
        } finally {
            lock.unlock();
        }
    }

    // ---- 结算入口：计息 → 到期清算 → 播报 ----

    /**
     * 收盘结算调用（{@code SettlementService.settle} 在融资计息之后、追保强平之前）。
     * 三步：① 各笔按账龄取档计息并滚入本金；② 到期（{@code dueDay <= day}）的笔先扣现金、
     * 不足则强制卖出持仓冲账；③ 按最紧急的程度挑一条台词播报。
     * 全程只改传入的 user / positions（与 {@code MarginService.liquidate} 同一套原地改账约定），
     * 调用方随后重算净资产再评价与快照。
     */
    public void settleDay(long userId, int day, SysUser user, List<UserPosition> positions,
                          QuoteBoard board, GameMode mode, long worldSeed) {
        List<UserSharkLoan> loans = openLoans(userId);
        if (loans.isEmpty()) {
            syncBalance(user, loans);
            return;
        }
        // ① 计息（利滚利：利息进余额，明天按更大的基数再算）
        BigDecimal interest = ZERO;
        boolean rateSteppedUp = false;
        for (UserSharkLoan loan : loans) {
            // 涨档是玩家唯一该被打扰的时刻之一：今天这一档和昨天不一样
            if (day > loan.getStartDay() && annualRateAt(loan, day) != annualRateAt(loan, day - 1)) {
                rateSteppedUp = true;
            }
            BigDecimal add = dailyInterestOf(loan.getBalance(), annualRateAt(loan, day), mode);
            if (add.signum() <= 0) {
                continue;
            }
            loan.setBalance(loan.getBalance().add(add));
            sharkLoanMapper.updateById(loan);
            interest = interest.add(add);
        }
        if (interest.signum() > 0) {
            syncBalance(user, loans);
            sysUserMapper.updateById(user);
            insertLoan(userId, TYPE_INTEREST, interest, sharkOf(user), day);
        }

        // ② 到期清算：现金优先，不足则按市值从大到小强卖（停牌 / 跌停封死 / T+1 未解冻跳过）
        boolean forced = false;
        boolean clearedDue = false;
        boolean overdue = false;
        for (UserSharkLoan loan : loans) {
            if (loan.getDueDay() > day) {
                continue;
            }
            repayFromCash(user, loan);
            if (loan.getBalance().signum() > 0) {
                forced |= forceSellFor(userId, day, user, loan, positions, board);
            }
            if (loan.getBalance().signum() <= 0) {
                loan.setStatus(STATUS_REPAID);
                clearedDue = true;
            } else {
                loan.setStatus(STATUS_OVERDUE);
                overdue = true;
            }
            sharkLoanMapper.updateById(loan);
        }
        syncBalance(user, loans);
        sysUserMapper.updateById(user);

        // ③ 播报：只在"事情变了"的节点打扰玩家，一天最多一条。
        //    优先级：到期强卖 > 逾期 > 到期结清 > 临近到期（剩 3 天 / 剩 1 天）> 利率涨档。
        //    平时静默——利息照滚、流水照记，但不再每天推一条催债（实测一局能刷十几条）。
        int daysToNearestDue = Integer.MAX_VALUE;
        for (UserSharkLoan loan : loans) {
            if (!STATUS_REPAID.equals(loan.getStatus()) && loan.getDueDay() > day) {
                daysToNearestDue = Math.min(daysToNearestDue, loan.getDueDay() - day);
            }
        }
        Random rng = WorldRandom.of(worldSeed, "shark" + day);
        SharkLines.Scene scene = null;
        String title = null;
        if (forced) {
            scene = SharkLines.Scene.LIQUIDATED;
            title = "高利贷到期清算";
        } else if (overdue) {
            scene = SharkLines.Scene.OVERDUE;
            title = "高利贷逾期";
        } else if (clearedDue) {
            scene = SharkLines.Scene.DUE_TODAY;
            title = "高利贷到期结清";
        } else if (daysToNearestDue == 3 || daysToNearestDue == 1) {
            scene = SharkLines.Scene.DUE_SOON;
            title = "高利贷临近到期";
        } else if (rateSteppedUp) {
            scene = SharkLines.Scene.CHATTER;
            title = "高利贷涨息";
        }
        if (scene != null) {
            String line = SharkLines.pick(scene, rng) + " 未清余额 " + money(sharkOf(user))
                    + " 元，今日利息 " + money(interest) + " 元。";
            if (forced || overdue) {
                // 到期强卖与逾期是"刀已经落下来"，弹窗暂停让玩家看清楚少了什么
                notifyPublisher.alert(userId, day, title, line, null);
            } else {
                notifyPublisher.system(userId, day, title, line, null);
            }
        }
    }

    // ---- 内部：现金冲账 / 强卖 / 台账同步 ----

    /** 现金还高利贷：还到现金见底或本笔清零为止。 */
    private void repayFromCash(SysUser user, UserSharkLoan loan) {
        BigDecimal paid = user.getBalance().min(loan.getBalance());
        if (paid.signum() <= 0) {
            return;
        }
        user.setBalance(user.getBalance().subtract(paid));
        loan.setBalance(loan.getBalance().subtract(paid));
    }

    /**
     * 到期强卖：持仓按市值从大到小逐笔卖出，净额先入账再冲本笔余额——
     * 与 {@code MarginService.liquidate} 同一套跳过规则（停牌 / 跌停封死 / 可卖 0 跳过），
     * 区别只在还的是高利贷、目标清零而不是回到担保比例线。
     */
    private boolean forceSellFor(long userId, int day, SysUser user, UserSharkLoan loan,
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
            if (loan.getBalance().signum() <= 0) {
                break;
            }
            Quote quote = board.quote(position.getStockId());
            if (quote.isHalted() || !quote.canSell() || position.getAvailableShares() <= 0) {
                continue;
            }
            int shares = Math.min(position.getShares(), position.getAvailableShares());
            BigDecimal price = BigDecimal.valueOf(quote.getCurrent()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(shares));
            BigDecimal commission = amount.multiply(BigDecimal.valueOf(GameConstants.COMMISSION_RATE))
                    .setScale(2, RoundingMode.HALF_UP)
                    .max(BigDecimal.valueOf(GameConstants.COMMISSION_MIN).setScale(2, RoundingMode.HALF_UP));
            BigDecimal tax = amount.multiply(BigDecimal.valueOf(GameConstants.STAMP_TAX_RATE))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = amount.subtract(commission).subtract(tax);

            user.setBalance(user.getBalance().add(net));
            BigDecimal paid = user.getBalance().min(loan.getBalance());
            user.setBalance(user.getBalance().subtract(paid));
            loan.setBalance(loan.getBalance().subtract(paid));

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
            insertTrade(userId, position.getStockId(), price, shares, amount, commission, tax, day,
                    TradeService.chartTickIndex(board));
            insertLoan(userId, TYPE_LIQUIDATE, paid, sharkOf(user), day);
            sold = true;
        }
        return sold;
    }

    /** 还款冲账：按到期日升序逐笔冲（先还到期近的），溢出的钱继续往后冲。原地改台账行。 */
    private void applyRepayment(List<UserSharkLoan> loans, BigDecimal amount, int day) {
        BigDecimal left = amount;
        for (UserSharkLoan loan : loans) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal paid = left.min(loan.getBalance());
            loan.setBalance(loan.getBalance().subtract(paid));
            left = left.subtract(paid);
            if (loan.getBalance().signum() <= 0) {
                loan.setStatus(STATUS_REPAID);
            }
            sharkLoanMapper.updateById(loan);
        }
    }

    /** 台账是权威：sys_user.shark_debt 永远等于未清各笔之和，重算而非加减。 */
    private void syncBalance(SysUser user, List<UserSharkLoan> loans) {
        BigDecimal sum = ZERO;
        for (UserSharkLoan loan : loans) {
            if (!STATUS_REPAID.equals(loan.getStatus())) {
                sum = sum.add(loan.getBalance());
            }
        }
        user.setSharkDebt(sum.setScale(2, RoundingMode.HALF_UP));
    }

    // ---- 内部：读取与落库小工具 ----

    /** 未清台账（OPEN + OVERDUE），按到期日升序——还款与视图都用这个顺序。 */
    private List<UserSharkLoan> openLoans(long userId) {
        return sharkLoanMapper.selectList(new LambdaQueryWrapper<UserSharkLoan>()
                .eq(UserSharkLoan::getUserId, userId)
                .in(UserSharkLoan::getStatus, List.of(STATUS_OPEN, STATUS_OVERDUE))
                .orderByAsc(UserSharkLoan::getDueDay)
                .orderByAsc(UserSharkLoan::getId));
    }

    private SharkLoanDTO toDto(long userId, SysUser user, List<UserSharkLoan> loans, QuoteBoard board,
                               int day, GameMode mode) {
        SharkLoanDTO dto = new SharkLoanDTO();
        dto.setBalance(sharkOf(user));
        dto.setBorrowable(borrowableOf(user, marketValueOf(positions(userId), board)));
        BigDecimal today = ZERO;
        double rate = 0.0;
        for (UserSharkLoan loan : loans) {
            today = today.add(dailyInterestOf(loan.getBalance(), annualRateAt(loan, day), mode));
            rate = Math.max(rate, annualRateAt(loan, day));
        }
        dto.setTodayInterest(today);
        dto.setAnnualRate(rate);
        if (!loans.isEmpty()) {
            dto.setDueDay(loans.get(0).getDueDay());
            dto.setDaysToDue(loans.get(0).getDueDay() - day);
        }
        dto.setLimitRatio(GameConstants.SHARK_LIMIT_RATIO);
        dto.setBaseAnnual(GameConstants.SHARK_ANNUAL_BASE);
        dto.setStepAnnual(GameConstants.SHARK_ANNUAL_STEP);
        dto.setStepDays(GameConstants.SHARK_RATE_STEP_DAYS);
        dto.setMaxAnnual(GameConstants.SHARK_ANNUAL_MAX);
        dto.setTermDays(mode.getDaysPerYear() * GameConstants.SHARK_TERM_YEARS);
        dto.setDaysPerYear(mode.getDaysPerYear());
        dto.setWarnings(SharkLines.pool(SharkLines.Scene.WARNING));
        dto.setTranches(loans.stream().map((loan) -> {
            SharkLoanDTO.Tranche row = new SharkLoanDTO.Tranche();
            row.setId(loan.getId());
            row.setPrincipal(loan.getPrincipal());
            row.setBalance(loan.getBalance());
            row.setStartDay(loan.getStartDay());
            row.setDueDay(loan.getDueDay());
            row.setStatus(loan.getStatus());
            return row;
        }).toList());
        return dto;
    }

    private BigDecimal marketValueOf(List<UserPosition> holdings, QuoteBoard board) {
        BigDecimal total = ZERO;
        for (UserPosition holding : holdings) {
            total = total.add(BigDecimal.valueOf(board.quote(holding.getStockId()).getCurrent())
                    .multiply(BigDecimal.valueOf(holding.getShares())));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private List<UserPosition> positions(long userId) {
        return userPositionMapper.selectList(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId));
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

    private SysUser requireUser(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return user;
    }

    private UserGameState gameState(long userId) {
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
        if (gs == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gs;
    }

    private int currentDay(long userId) {
        return gameState(userId).getCurrentDay();
    }

    /** 终态拒绝借还（口径同融资：本局已结束就什么都别动了）。 */
    private void requireLoanState(UserGameState gs) {
        MarketState state = MarketState.valueOf(gs.getMarketState());
        if (state == MarketState.FINISHED || state == MarketState.BANKRUPT) {
            throw new BizException(ErrorCode.FINISHED_STATE);
        }
    }

    private BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private void insertLoan(long userId, String type, BigDecimal amount, BigDecimal debtAfter, int day) {
        LoanRecord record = new LoanRecord();
        record.setUserId(userId);
        record.setLoanType(type);
        record.setAmount(amount);
        record.setDebtAfter(debtAfter);
        record.setGameDay(day);
        loanRecordMapper.insert(record);
    }

    private void insertTrade(long userId, long stockId, BigDecimal price, int shares, BigDecimal amount,
                             BigDecimal commission, BigDecimal tax, int day, int tickIndex) {
        TradeRecord record = new TradeRecord();
        record.setUserId(userId);
        record.setStockId(stockId);
        record.setTradeType("SELL");
        record.setPrice(price);
        record.setShares(shares);
        record.setAmount(amount);
        record.setCommission(commission);
        record.setTax(tax);
        record.setGameDay(day);
        record.setTickIndex(tickIndex);
        tradeRecordMapper.insert(record);
    }

    private static String money(BigDecimal value) {
        return String.format("%,.2f", value);
    }
}
