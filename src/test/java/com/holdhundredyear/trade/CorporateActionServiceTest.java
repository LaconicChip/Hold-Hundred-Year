package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.market.MarketStateService;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.WorldTickService;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业行动服务集成测试（hold_year_test 库，方法级事务自动回滚）：覆盖 spec §6.5
 * /交接 §5 Task 9.1 的 A 股特色机制——
 * <ul>
 * <li>ST 戴帽 → 涨跌停收窄 ±5% 且 stock_state.is_st 落库；摘帽恢复 ±10%；</li>
 * <li>每股每局至多戴帽 1 次（内存去重，clearUserState 后可再戴）；</li>
 * <li>10转10 除权：持仓股数翻倍、均价减半、市值守恒、涨跌停基准按除权后 prevClose；</li>
 * <li>现金分红除息：现金入账、股价除息、净资产守恒；</li>
 * <li>除权后首跳仍被钳制在按新基准计算的涨跌停内（时序）；</li>
 * <li>事件工厂掷出 fund-st-hat / fund-high-transfer 骨架时真实触发企业行动（接线）。</li>
 * </ul>
 * 第 1 日开盘价即基准价（全股票 current=prevClose=basePrice），断言期望值硬编码。
 */
@SpringBootTest
@Transactional
class CorporateActionServiceTest {

    /** 测试用股票（茅王酒业，现价 168.00 = prevClose，基准价）。 */
    private static final long STOCK = 1L;
    private static final double BASE = 168.00;

    @Autowired private CorporateActionService corporateActionService;
    @Autowired private WorldTickService worldTickService;
    @Autowired private MarketStateService marketStateService;
    @Autowired private WorldSeeder worldSeeder;
    @Autowired private QuoteBoardRegistry quoteBoardRegistry;
    @Autowired private UserGameStateMapper userGameStateMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private UserPositionMapper userPositionMapper;
    @Autowired private UserStockStateMapper userStockStateMapper;
    @Autowired private UserMessageMapper userMessageMapper;

    // ---- ST 戴帽/摘帽 ----

    @Test
    void hatNarrowsBandToFivePercentAndPersistsSt() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote quote = board.quote(STOCK);
        assertFalse(quote.isSt(), "a freshly seeded stock must start non-ST");

        assertTrue(corporateActionService.hat(userId, board, STOCK, 1), "first hat must apply");
        assertTrue(quote.isSt(), "quote must be marked ST after hat");
        assertEquals(round2(BASE * 1.05), quote.limitUpPrice(), 0.0,
                "ST stock must cap at +5%");
        assertEquals(round2(BASE * 0.95), quote.limitDownPrice(), 0.0,
                "ST stock must floor at -5%");
        assertTrue(Boolean.TRUE.equals(stockState(userId, STOCK).getIsSt()),
                "user_stock_state.is_st must be persisted true");
    }

    @Test
    void unhatRestoresTenPercentBand() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote quote = board.quote(STOCK);
        quote.setSt(true);
        stockState(userId, STOCK).setIsSt(true);

        corporateActionService.unhat(userId, board, STOCK, 1);

        assertFalse(quote.isSt(), "quote must no longer be ST after unhat");
        assertEquals(round2(BASE * 1.10), quote.limitUpPrice(), 0.0,
                "unhatted stock must restore +10% limit up");
        assertEquals(round2(BASE * 0.90), quote.limitDownPrice(), 0.0,
                "unhatted stock must restore -10% limit down");
        assertFalse(Boolean.TRUE.equals(stockState(userId, STOCK).getIsSt()),
                "user_stock_state.is_st must be persisted false");
    }

    @Test
    void atMostOneHatPerStockAndClearResets() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);

        assertTrue(corporateActionService.hat(userId, board, STOCK, 1), "first hat must apply");
        assertFalse(corporateActionService.hat(userId, board, STOCK, 1),
                "a second hat on the same stock must be rejected (per-stock once per game)");
        assertTrue(Boolean.TRUE.equals(stockState(userId, STOCK).getIsSt()),
                "is_st must stay true after a rejected re-hat");

        assertTrue(corporateActionService.hat(userId, board, 2L, 1),
                "a different stock may still be hatted");

        corporateActionService.clearUserState(userId);
        assertTrue(corporateActionService.hat(userId, board, STOCK, 1),
                "clearUserState must allow the same stock to be hatted again");
    }

    // ---- 10转10 除权 ----

    @Test
    void split10to10DoublesSharesHalvesCostAndConservesMarketValue() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote quote = board.quote(STOCK);
        insertPosition(userId, STOCK, 200, 200, "168.05");
        double marketValueBefore = 200 * quote.getCurrent();

        corporateActionService.split10to10(userId, board, STOCK, 1);

        UserPosition position = position(userId, STOCK);
        assertNotNull(position);
        assertEquals(400, position.getShares(), "shares must double");
        assertEquals(400, position.getAvailableShares(), "available_shares must double");
        assertEquals(0, new BigDecimal("84.03").compareTo(position.getAvgCost()),
                "avg cost must halve (168.05 / 2 = 84.025 -> 84.03)");
        assertEquals(round2(BASE / 2), quote.getPrevClose(), 0.0, "prev_close must halve");
        assertEquals(round2(BASE / 2), quote.getCurrent(), 0.0, "current price must halve");
        assertEquals(round2(BASE / 2), stockState(userId, STOCK).getPrevClose().doubleValue(), 0.0,
                "stock_state.prev_close must stay in sync");
        assertEquals(round2(BASE / 2), stockState(userId, STOCK).getCurrentPrice().doubleValue(), 0.0,
                "stock_state.current_price must stay in sync");

        double marketValueAfter = position.getShares() * quote.getCurrent();
        assertEquals(marketValueBefore, marketValueAfter, 0.01,
                "market value must be conserved across a 10-to-10 split");
    }

    /**
     * 除权必须折算**整个当日口径**（回归玩家报的"股灾后除权，日K上多出一根跌 50% 的巨长阴线，
     * 鼠标却只写 −10%"）：只折 prevClose/current 的话，当日那根 bar 会留着除权前的 open/high
     * 去配除权后的 close，分时线与均价线同理。所以把四价铺开到互不相同的值逐个断言，
     * 并确认折算后 high/low 仍然包住实体（图上不会自己画出一根不存在的针）。
     */
    @Test
    void splitFoldsWholeDayOhlcIntradayAndPersistedState() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote quote = board.quote(STOCK);
        quote.setOpen(169.00);
        quote.setHigh(172.00);
        quote.setLow(166.00);
        quote.setCurrent(170.00);
        quote.appendTick(170.00);

        corporateActionService.split10to10(userId, board, STOCK, 1);

        assertEquals(84.50, quote.getOpen(), 0.0, "open must fold to the ex-rights scale");
        assertEquals(86.00, quote.getHigh(), 0.0,
                "high must fold too, otherwise the daily bar keeps a pre-split upper wick");
        assertEquals(83.00, quote.getLow(), 0.0, "low must fold to the ex-rights scale");
        assertEquals(85.00, quote.getCurrent(), 0.0, "current must fold to the ex-rights scale");
        assertTrue(quote.getHigh() >= Math.max(quote.getOpen(), quote.getCurrent()),
                "the folded bar must stay self-consistent: high >= max(open, close)");
        assertTrue(quote.getLow() <= Math.min(quote.getOpen(), quote.getCurrent()),
                "the folded bar must stay self-consistent: low <= min(open, close)");
        assertEquals(85.00, quote.getIntraday().getLast(), 0.0, "the intraday line must fold too");
        assertEquals(85.00, quote.getDayAvg(), 0.0, "the day-average line must fold too");

        UserStockState state = stockState(userId, STOCK);
        assertEquals(quote.getPrevClose(), state.getPrevClose().doubleValue(), 0.0,
                "state.prev_close must follow the folded quote");
        assertEquals(quote.getOpen(), state.getOpenPrice().doubleValue(), 0.0,
                "state.open_price must follow the quote - settlement reads these very columns for the daily bar");
        assertEquals(quote.getHigh(), state.getHighPrice().doubleValue(), 0.0,
                "state.high_price must follow the quote");
        assertEquals(quote.getLow(), state.getLowPrice().doubleValue(), 0.0,
                "state.low_price must follow the quote");
        assertEquals(quote.getCurrent(), state.getCurrentPrice().doubleValue(), 0.0,
                "state.current_price must follow the quote");
    }

    // ---- 现金分红 ----

    @Test
    void cashDividendCreditsCashAndConservesNetAsset() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote quote = board.quote(STOCK);
        insertPosition(userId, STOCK, 300, 300, "160.00");
        double netAssetBefore = balance(userId).doubleValue() + 300 * quote.getCurrent();

        corporateActionService.cashDividend(userId, board, STOCK, 1.0, 1);

        assertEquals(0, asDecimal("100300.00").compareTo(balance(userId)),
                "300 shares x 1.00/share must credit 300.00 cash");
        assertEquals(round2(BASE - 1.0), quote.getCurrent(), 0.0, "current must be price-divided down by 1.00");
        assertEquals(round2(BASE - 1.0), quote.getPrevClose(), 0.0, "prev_close must be price-divided down by 1.00");
        assertEquals(round2(BASE - 1.0), stockState(userId, STOCK).getCurrentPrice().doubleValue(), 0.0,
                "stock_state.current_price must stay in sync");

        double netAssetAfter = balance(userId).doubleValue() + 300 * quote.getCurrent();
        assertEquals(netAssetBefore, netAssetAfter, 0.01,
                "net asset must be conserved across the dividend (cash up, market value down)");
    }

    /** 除息同属"当日整体折算"：开盘/最高/最低也要跟着减派息额，不能只动昨收与现价。 */
    @Test
    void cashDividendFoldsIntradayRangeAndPersistsState() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote quote = board.quote(STOCK);
        quote.setOpen(169.00);
        quote.setHigh(172.00);
        quote.setLow(166.00);
        quote.setCurrent(170.00);

        corporateActionService.cashDividend(userId, board, STOCK, 1.0, 1);

        assertEquals(167.00, quote.getPrevClose(), 0.0, "prev_close must be ex-dividend");
        assertEquals(168.00, quote.getOpen(), 0.0, "open must be ex-dividend too");
        assertEquals(171.00, quote.getHigh(), 0.0, "high must be ex-dividend too");
        assertEquals(165.00, quote.getLow(), 0.0, "low must be ex-dividend too");
        assertEquals(169.00, quote.getCurrent(), 0.0, "current must be ex-dividend too");

        UserStockState state = stockState(userId, STOCK);
        assertEquals(168.00, state.getOpenPrice().doubleValue(), 0.0, "state.open_price must be ex-dividend");
        assertEquals(171.00, state.getHighPrice().doubleValue(), 0.0, "state.high_price must be ex-dividend");
        assertEquals(165.00, state.getLowPrice().doubleValue(), 0.0, "state.low_price must be ex-dividend");
    }

    // ---- 时序：除权后首跳仍钳制在按新基准计算的涨跌停内 ----

    @Test
    void splitThenNextTickClampsToNewExRightsBand() {
        long userId = seedInMarket();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        Quote quote = board.quote(STOCK);
        insertPosition(userId, STOCK, 100, 100, "168.00");

        corporateActionService.split10to10(userId, board, STOCK, 1);
        Quote q = board.quote(STOCK);
        double exRightsPrevClose = round2(BASE / 2);
        assertEquals(exRightsPrevClose, q.getPrevClose(), 0.0, "split must regenerate prev_close as the new basis");
        double newUp = q.limitUpPrice();
        double newDown = q.limitDownPrice();
        // above the new +10% band yet still inside the pre-split band of 168.00 -> exercise the new basis
        q.setCurrent(round2(exRightsPrevClose * 1.15));

        worldTickService.tickOne(userId);

        double price = q.getCurrent();
        assertTrue(price >= newDown - 1e-9 && price <= newUp + 1e-9,
                "next tick must clamp the price into the ex-rights band [" + newDown + ", " + newUp
                        + "], got " + price);
        assertTrue(price <= newUp + 1e-9,
                "the newly narrowed limit must bind (price " + price + " vs limit-up " + newUp + ")");
    }

    // ---- 事件接线：fund-st-hat / fund-high-transfer 真实触发企业行动 ----

    @Test
    void stHatAndHighTransferEventsDispatchCorporateActions() {
        // 接线测试不能靠运气等某个种子：骨架池是加权抽签，池子增删会让抽签序列整体换位
        // （notes §7 记着这个代价）。所以按固定顺序扫 1..12 号种子、每个跑 600 跳，
        // 取第一个真的掷出 fund-st-hat 的世界——扫法确定，不依赖"历史上某个号一定出"。
        long hatUser = 0L;
        UserMessage hatMessage = null;
        for (long seed = 1; hatMessage == null && seed <= 12; seed++) {
            hatUser = seedInMarket(seed);
            advance(hatUser, 600);
            hatMessage = notificationTitled(hatUser, "ST 戴帽");
        }
        assertNotNull(hatMessage, "no seed in 1..12 rolled fund-st-hat within 600 ticks: "
                + "the skeleton pool moved, widen the scan instead of deleting the wiring check");
        assertTrue(Boolean.TRUE.equals(stockState(hatUser, hatMessage.getStockId()).getIsSt()),
                "the hatted stock must persist is_st=true");

        // 高送转同样不能钉死某个"幸运种子"：池子从 92 涨到 160 之后，加权抽签序列整体换位，
        // 老种子在前 2000 跳里不再掷出 fund-high-transfer（实测就是这条挂掉的）。改成按固定顺序
        // 扫种子、取第一个真掷出除权的世界——扫法确定，不依赖"历史上某个号一定出"。
        long splitUser = 0L;
        boolean sawSplit = false;
        for (long seed = 101; !sawSplit && seed <= 140; seed++) {
            splitUser = seedInMarket(seed);
            insertPositionsForAllStocks(splitUser);
            advance(splitUser, 2000);
            for (UserMessage m : corporateActionMessages(splitUser)) {
                if ("10转10 除权".equals(m.getTitle())) {
                    UserPosition p = position(splitUser, m.getStockId());
                    assertNotNull(p, "split must act on a real position row");
                    assertTrue(p.getShares() >= 200,
                            "split must double the 100-share baseline holding, got " + p.getShares());
                    sawSplit = true;
                }
            }
        }
        assertTrue(sawSplit, "fund-high-transfer must dispatch a 10转10 除权 corporate action");
    }

    // ---- 辅助 ----

    private long seedInMarket() {
        return seedInMarket(20260904L);
    }

    private long seedInMarket(long worldSeed) {
        long userId = worldSeeder.seedUser("corp_" + System.nanoTime(), "$2a$10$bcrypthash");
        UserGameState gs = gameState(userId);
        gs.setWorldSeed(worldSeed);
        userGameStateMapper.updateById(gs);
        marketStateService.startDay(userId);
        return userId;
    }

    /** 推进若干跳，自动处理午休与跨日边界。 */
    private void advance(long userId, int ticks) {
        for (int i = 0; i < ticks; i++) {
            worldTickService.tickOne(userId);
            handleBoundary(userId);
        }
    }

    /** 午休恢复下午交易；收盘后开启下一交易日。 */
    private void handleBoundary(long userId) {
        String state = gameState(userId).getMarketState();
        switch (state) {
            case "LUNCH_BREAK" -> marketStateService.resumeAfternoon(userId);
            case "CLOSED" -> marketStateService.startDay(userId);
            default -> { }
        }
    }

    /** 为全部 24 只股票各埋 100 股基准仓位，使任意 fund-high-transfer 的翻倍可观测。 */
    private void insertPositionsForAllStocks(long userId) {
        for (StockDefs.StockDef def : StockDefs.ALL) {
            if (position(userId, def.id()) == null) {
                insertPosition(userId, def.id(), 100, 100, String.valueOf(def.basePrice()));
            }
        }
    }

    /** 企业行动类通知消息（source=SYSTEM、type=NOTIFY）。 */
    private List<UserMessage> corporateActionMessages(long userId) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getType, "NOTIFY"));
    }

    /** 指定企业行动标题的首条通知。 */
    private UserMessage notificationTitled(long userId, String title) {
        return corporateActionMessages(userId).stream()
                .filter(m -> title.equals(m.getTitle())).findFirst().orElse(null);
    }

    private void insertPosition(long userId, long stockId, int shares, int availableShares, String avgCost) {
        UserPosition position = new UserPosition();
        position.setUserId(userId);
        position.setStockId(stockId);
        position.setShares(shares);
        position.setAvailableShares(availableShares);
        position.setAvgCost(new BigDecimal(avgCost));
        userPositionMapper.insert(position);
    }

    private UserPosition position(long userId, long stockId) {
        return userPositionMapper.selectOne(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId)
                .eq(UserPosition::getStockId, stockId));
    }

    private UserStockState stockState(long userId, long stockId) {
        return userStockStateMapper.selectOne(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId)
                .eq(UserStockState::getStockId, stockId));
    }

    private BigDecimal balance(long userId) {
        return sysUser(userId).getBalance();
    }

    private SysUser sysUser(long userId) {
        return sysUserMapper.selectById(userId);
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }

    private static double round2(double v) {
        return Quote.round2(v);
    }

    private static BigDecimal asDecimal(String value) {
        return new BigDecimal(value);
    }
}