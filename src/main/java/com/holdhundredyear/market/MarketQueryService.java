package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.constants.PreHistoryDefs;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.dto.DailySummaryDTO;
import com.holdhundredyear.game.model.dto.EquityCurveDTO;
import com.holdhundredyear.game.model.dto.IndexPointDTO;
import com.holdhundredyear.game.model.dto.IntradayPointDTO;
import com.holdhundredyear.game.model.dto.KlinePointDTO;
import com.holdhundredyear.game.model.dto.LeaderboardDTO;
import com.holdhundredyear.game.model.dto.MarketStateDTO;
import com.holdhundredyear.game.model.dto.StockQuoteDTO;
import com.holdhundredyear.game.model.enums.EraCode;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.entity.StockDailyBar;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserEra;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserMessage;
import com.holdhundredyear.market.mapper.StockDailyBarMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserEraMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserMessageMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.holdhundredyear.world.WorldRandom;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 市场查询聚合服务（Task 10.3，spec §11 市场侧只读接口）：state 聚合、daily-summary
 * 组装、leaderboard 与 equity-curve 查询。只读无锁——与 GameEndService.result 同一读取
 * 模式：数据源为 user_daily_snapshot（快照为唯一事实），排行榜 NPC 行解析自
 * npc_assets_json、玩家行以净资产追加（rankings/curve 为 private，这里等价重实现）。
 * skip/start 的编排留在 REST 层（MarketController），本服务仅提供查询与
 * requireGameState 读取入口。
 */
@Service
public class MarketQueryService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 事件新闻来源（NPC/FUN/SYSTEM 均非新闻）。 */
    private static final Set<String> NEWS_SOURCES = Set.of("AUTHORITY", "MEDIA", "RUMOR", "INSIDER");

    /** 玩梗评语：大幅跑赢（超额 >= 3 个百分点）。 */
    private static final List<String> BIG_WIN_COMMENTS = List.of(
            "今天你就是营业部最靓的仔，老王都开始找你要代码了。",
            "跑赢大盘这么多，建议明天直接去财经频道坐镇。",
            "这收益率，张大妈买菜都要先问问你买什么票。",
            "账户红得发紫，晚上加个鸡腿不过分。",
            "别人恐高你起飞，今天你就是风口本身。");

    /** 玩梗评语：跑赢（0 <= 超额 < 3）。 */
    private static final List<String> WIN_COMMENTS = List.of(
            "小赚一点，落袋为安，稳字当头。",
            "跑赢大盘，虽不高调，但足够体面。",
            "今天账户的绿色只属于大盘，不属于你。",
            "稳步向前，散户的自我修养就是慢慢变富。");

    /** 玩梗评语：跑输（-3 < 超额 < 0）。 */
    private static final List<String> LOSS_COMMENTS = List.of(
            "小亏怡情，就当给市场交了学费。",
            "跑输大盘不多，权当看了场付费直播。",
            "绿了，但没全绿，下周还能抢救一下。",
            "股神也有走神的一天，先捂住别慌。");

    /** 玩梗评语：大幅跑输（超额 <= -3）。 */
    private static final List<String> BIG_LOSS_COMMENTS = List.of(
            "今天这根大阴线，建议裱起来留作纪念。",
            "别人割肉你断腕，营业部今日最佳悲情主角。",
            "账户绿得像韭菜地，你本人就是那茬韭菜。",
            "关灯吃面，面里记得加个蛋，安抚一下自己。",
            "跌得这么有排面，隔壁老王都忍不住来安慰你两句。");

    private final UserGameStateMapper userGameStateMapper;
    private final UserEraMapper userEraMapper;
    private final UserDailySnapshotMapper userDailySnapshotMapper;
    private final UserMessageMapper userMessageMapper;
    private final StockDailyBarMapper stockDailyBarMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;

    public MarketQueryService(UserGameStateMapper userGameStateMapper,
                              UserEraMapper userEraMapper,
                              UserDailySnapshotMapper userDailySnapshotMapper,
                              UserMessageMapper userMessageMapper,
                              StockDailyBarMapper stockDailyBarMapper,
                              QuoteBoardRegistry quoteBoardRegistry) {
        this.userGameStateMapper = userGameStateMapper;
        this.userEraMapper = userEraMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.userMessageMapper = userMessageMapper;
        this.stockDailyBarMapper = stockDailyBarMapper;
        this.quoteBoardRegistry = quoteBoardRegistry;
    }

    /**
     * 市场状态聚合：状态机值、年/年内日换算、当日纪元、段位、盘中打点与倒计时
     * （盘中 = 全天跳数 - tickIndex，其余状态 0）、世界种子、本局模式档案。无 game_state 抛 NOT_FOUND。
     */
    public MarketStateDTO state(long userId) {
        UserGameState gameState = requireGameState(userId);
        int currentDay = gameState.getCurrentDay();

        MarketStateDTO dto = new MarketStateDTO();
        dto.setMarketState(gameState.getMarketState());
        dto.setCurrentDay(currentDay);
        GameMode mode = GameMode.of(gameState.getGameMode());
        dto.setYear((currentDay - 1) / mode.getDaysPerYear() + 1);
        dto.setDayInYear((currentDay - 1) % mode.getDaysPerYear() + 1);
        dto.setMode(mode.name());
        dto.setTickMs(mode.getTickMs());
        dto.setDaySeconds(mode.daySeconds());
        dto.setTotalDays(mode.totalDays());
        dto.setLimitPct(mode.getLimitPct());
        UserEra era = eraForDay(userId, currentDay);
        if (era != null) {
            dto.setEraCode(era.getEraCode());
            dto.setEraName(EraCode.valueOf(era.getEraCode()).getName());
        }
        dto.setTier(gameState.getTier());
        int liveTick = liveTickIndex(userId, gameState);
        dto.setTickIndex(liveTick);
        MarketState marketState = MarketState.valueOf(gameState.getMarketState());
        boolean trading = marketState == MarketState.MORNING_OPEN || marketState == MarketState.AFTERNOON_OPEN;
        // 倒计时按"当前这一段还剩几跳"算：早盘只有 45 跳，用全天 90 跳做基准会让早盘
        // 显示成午盘的两倍长（tick_index 是全天绝对跳号 0..90）。
        dto.setCountdownTicks(remainingTicksInSession(marketState, liveTick));
        dto.setWorldSeed(String.valueOf(gameState.getWorldSeed()));
        // 指数块当日涨跌幅（大盘页 StatusBar）：昨收取前日快照，无前日快照按基期 1000
        double prevIndex = GameConstants.INITIAL_INDEX;
        UserDailySnapshot prevSnapshot = snapshotAt(userId, currentDay - 1);
        if (prevSnapshot != null) {
            prevIndex = prevSnapshot.getIndexValue();
        }
        dto.setIndexValue(gameState.getIndexValue());
        dto.setPrevIndex(prevIndex);
        dto.setIndexDayPct(prevIndex > 0 ? (gameState.getIndexValue() / prevIndex - 1) * 100 : 0.0);
        // 暂停标记只在行情板已缓存时可读；没缓存说明这个世界当前没有在推，按未暂停下发
        QuoteBoard board = quoteBoardRegistry.peek(userId);
        dto.setPaused(board != null && board.isPaused());
        return dto;
    }

    /**
     * 单日收官总结：gameDay 为空取最近结算日（user_daily_snapshot 的 max(game_day)），
     * 指定日无快照或全无快照抛 NOT_FOUND。新闻/通知为当日消息标题（按落库序），
     * 评语按当日超额收益（dayReturnPct - indexDayPct）四档随机抽取，盐 "comment"+day
     * 保证同 seed 同日确定性。
     */
    public DailySummaryDTO dailySummary(long userId, Integer gameDay) {
        UserDailySnapshot snapshot = gameDay == null
                ? latestSnapshot(userId)
                : snapshotAt(userId, gameDay);
        if (snapshot == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        int day = snapshot.getGameDay();
        UserGameState gameState = requireGameState(userId);

        double dayReturnPct = snapshot.getDayReturnPct() != null ? snapshot.getDayReturnPct() : 0.0;
        double indexDayPct = snapshot.getIndexDayPct() != null ? snapshot.getIndexDayPct() : 0.0;

        DailySummaryDTO dto = new DailySummaryDTO();
        dto.setGameDay(day);
        dto.setDayReturnPct(dayReturnPct);
        dto.setIndexDayPct(indexDayPct);
        dto.setCash(snapshot.getCash().doubleValue());
        dto.setMarketValue(snapshot.getMarketValue().doubleValue());
        double marginDebt = snapshot.getDebt().doubleValue();
        double sharkDebt = snapshot.getSharkDebt() == null ? 0.0 : snapshot.getSharkDebt().doubleValue();
        dto.setDebt(marginDebt + sharkDebt);
        dto.setSharkDebt(sharkDebt);
        dto.setNetAsset(snapshot.getNetAsset().doubleValue());
        // 担保比例只按融资债算：券商不为高利贷平仓（spec §6.6）
        dto.setGuardRatio(marginDebt > 0 ? (dto.getCash() + dto.getMarketValue()) / marginDebt : 0.0);

        List<UserMessage> messages = messagesOfDay(userId, day);
        dto.setNews(messages.stream()
                .filter(message -> NEWS_SOURCES.contains(message.getSource())
                        && "EVENT".equals(message.getType()))
                .map(UserMessage::getTitle)
                .toList());
        // 提醒栏收 NOTIFY 与 ALERT 两种（ALERT 是"弹窗暂停"那一档，但晚报里同样要列出来，
        // 否则追保/强平/停牌潮这些最要紧的话反而从收盘总结里消失了）
        dto.setNotices(messages.stream()
                .filter(message -> "SYSTEM".equals(message.getSource())
                        && ("NOTIFY".equals(message.getType()) || "ALERT".equals(message.getType())))
                .map(UserMessage::getTitle)
                .toList());
        dto.setComment(comment(gameState.getWorldSeed(), day, dayReturnPct - indexDayPct));

        UserEra era = eraForDay(userId, day);
        dto.setEraName(era != null ? EraCode.valueOf(era.getEraCode()).getName() : null);
        return dto;
    }

    /**
     * 营业部日排行榜：当日快照 npc_assets_json 的 8 个 NPC 行 + 玩家行（当日净资产，
     * gs 处于 BANKRUPT 时 status=BANKRUPT 否则 ACTIVE），按资产降序。默认最近结算日，
     * 无快照抛 NOT_FOUND。
     */
    public LeaderboardDTO leaderboard(long userId, Integer gameDay) {
        UserDailySnapshot snapshot = gameDay == null
                ? latestSnapshot(userId)
                : snapshotAt(userId, gameDay);
        if (snapshot == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        UserGameState gameState = requireGameState(userId);

        List<LeaderboardDTO.Row> rows = new ArrayList<>();
        String npcJson = snapshot.getNpcAssetsJson();
        if (npcJson != null && !npcJson.isBlank()) {
            JsonNode npcs = MAPPER.readTree(npcJson);
            for (int i = 0; i < npcs.size(); i++) {
                JsonNode npc = npcs.get(i);
                LeaderboardDTO.Row row = new LeaderboardDTO.Row();
                row.setName(npc.get("name").asString());
                row.setAsset(npc.get("asset").asDouble());
                row.setStatus(npc.get("status").asString());
                row.setIsPlayer(false);
                rows.add(row);
            }
        }
        LeaderboardDTO.Row player = new LeaderboardDTO.Row();
        player.setName("您");
        player.setAsset(snapshot.getNetAsset().doubleValue());
        player.setStatus(MarketState.BANKRUPT.name().equals(gameState.getMarketState())
                ? "BANKRUPT" : "ACTIVE");
        player.setIsPlayer(true);
        rows.add(player);
        rows.sort(Comparator.comparing(LeaderboardDTO.Row::getAsset).reversed());

        LeaderboardDTO dto = new LeaderboardDTO();
        dto.setGameDay(snapshot.getGameDay());
        dto.setRows(rows);
        return dto;
    }

    /** 资产 vs 指数曲线：game_day 0..最新结算日快照升序映射；全无快照抛 NOT_FOUND。 */
    public EquityCurveDTO equityCurve(long userId) {
        List<UserDailySnapshot> snapshots = userDailySnapshotMapper.selectList(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .orderByAsc(UserDailySnapshot::getGameDay));
        if (snapshots.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        EquityCurveDTO dto = new EquityCurveDTO();
        dto.setPoints(snapshots.stream().map(snapshot -> {
            EquityCurveDTO.Point point = new EquityCurveDTO.Point();
            point.setGameDay(snapshot.getGameDay());
            point.setNetAsset(snapshot.getNetAsset().doubleValue());
            point.setIndexValue(snapshot.getIndexValue());
            return point;
        }).toList());
        return dto;
    }

    /** 读取 game_state 供 REST 层终局校验等使用，缺失抛 NOT_FOUND。 */
    public UserGameState requireGameState(long userId) {
        UserGameState gameState = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gameState == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gameState;
    }

    /**
     * 24 只股票静态档案 + 本账户最新行情（spec §11 GET /api/stocks）：StockDefs 档案序，
     * 价格/涨跌幅/停牌/ST/封板读自行情板（世界锁内快照读取，changePct 为比率）。
     * 收盘/结算/终局态涨跌幅取持久化的当日收盘涨跌幅（Quote.lastClosePct）——
     * 结算 prev_close 双写后实时 dayChangePct() 恒为 0；盘中仍用实时涨跌幅。
     */
    public List<StockQuoteDTO> stocksWithQuotes(long userId) {
        UserGameState gameState = requireGameState(userId);
        boolean closed = isClosedLike(gameState.getMarketState());
        // 收盘/终局态：当日 bar 覆盖开高低与昨收——结算已把 state 的 prev_close/OHL
        // 双写重置为收盘价（为次日准备），bar 才是当日真实行情
        Map<Long, StockDailyBar> closedBars = closed
                ? barsOfDay(userId, gameState.getCurrentDay()) : Map.of();
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        board.getLock().lock();
        try {
            List<StockQuoteDTO> rows = new ArrayList<>(board.getQuotes().size());
            for (Quote quote : board.getQuotes().values()) {
                StockDefs.StockDef def = StockDefs.byId(quote.getStockId());
                StockDailyBar bar = closedBars.get(quote.getStockId());
                StockQuoteDTO row = new StockQuoteDTO();
                row.setStockId(quote.getStockId());
                row.setCode(def.code());
                row.setName(def.name());
                row.setSector(def.sector());
                row.setBasePrice(def.basePrice());
                row.setPrevClose(bar != null ? prevCloseOf(bar) : quote.getPrevClose());
                row.setOpen(bar != null ? bar.getOpen().doubleValue() : quote.getOpen());
                row.setCurrent(quote.getCurrent());
                row.setHigh(bar != null ? bar.getHigh().doubleValue() : quote.getHigh());
                row.setLow(bar != null ? bar.getLow().doubleValue() : quote.getLow());
                row.setChangePct(closed && quote.getLastClosePct() != null
                        ? quote.getLastClosePct() : quote.dayChangePct());
                // 涨跌停价按"展示用的昨收"算：收盘态昨收取当日日K bar，与 prevClose 字段同源
        double limitPct = quote.effectiveLimitPct();
        double shownPrevClose = row.getPrevClose();
        // 无涨跌停（ETF）下发 0：前端据此隐藏涨跌停价、也不再按涨停/跌停拦单
        row.setLimitUp(quote.isLimitless() ? 0.0 : Quote.limitPrice(shownPrevClose, limitPct, true));
        row.setLimitDown(quote.isLimitless() ? 0.0 : Quote.limitPrice(shownPrevClose, limitPct, false));
        row.setLimitUpStreak(quote.getLimitUpStreak());
                row.setHalted(quote.isHalted());
                row.setSt(quote.isSt());
                row.setSealed(quote.getSealed().name());
                row.setEtf(StockDefs.isEtf(quote.getStockId()));
                rows.add(row);
            }
            return rows;
        } finally {
            board.getLock().unlock();
        }
    }

    /** 当日该用户全部日K bar，键为 stockId（收盘态行情展示用）；无 bar 的股票不下发覆盖。 */
    private Map<Long, StockDailyBar> barsOfDay(long userId, int gameDay) {
        List<StockDailyBar> bars = stockDailyBarMapper.selectList(
                new LambdaQueryWrapper<StockDailyBar>()
                        .eq(StockDailyBar::getUserId, userId)
                        .eq(StockDailyBar::getGameDay, gameDay));
        Map<Long, StockDailyBar> byStock = new LinkedHashMap<>();
        for (StockDailyBar bar : bars) {
            byStock.put(bar.getStockId(), bar);
        }
        return byStock;
    }

    /** 当日昨收：由 bar 涨跌幅反推 close/(1+pct)——结算后 prev_close 列已是收盘价；缺涨跌幅退化收盘价。 */
    private static double prevCloseOf(StockDailyBar bar) {
        double close = bar.getClose().doubleValue();
        Double pct = bar.getChangePct();
        return pct != null && pct > -1 ? close / (1 + pct) : close;
    }

    /** 已收盘/结算/终局态：行情展示走当日收盘涨跌幅通道（day_change_pct）。 */
    private static boolean isClosedLike(String marketState) {
        return MarketState.CLOSED.name().equals(marketState)
                || MarketState.SETTLING.name().equals(marketState)
                || MarketState.FINISHED.name().equals(marketState)
                || MarketState.BANKRUPT.name().equals(marketState);
    }

    /**
     * 个股日K序列（GET /api/stocks/{id}/kline，**前复权**口径见 {@link #forwardAdjusted}）：
     * stock_daily_bar 按 game_day 升序、
     * limit 截取最近 N 根（1..本局 totalDays + 开局前历史根数夹逼，历史在 game_day <= 0，
     * 见 WorldSeeder#buildPreHistoryBars）。未知 stockId 抛 NOT_FOUND。
     */
    public List<KlinePointDTO> stockKline(long userId, long stockId, int limit) {
        UserGameState gs = requireGameState(userId);
        try {
            StockDefs.byId(stockId);
        } catch (IllegalArgumentException unknown) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        int capped = Math.max(1, Math.min(limit,
                GameMode.of(gs.getGameMode()).totalDays() + PreHistoryDefs.BARS));
        List<StockDailyBar> bars = stockDailyBarMapper.selectList(
                new LambdaQueryWrapper<StockDailyBar>()
                        .eq(StockDailyBar::getUserId, userId)
                        .eq(StockDailyBar::getStockId, stockId)
                        .orderByDesc(StockDailyBar::getGameDay)
                        .last("LIMIT " + capped));
        Collections.reverse(bars);
        return forwardAdjusted(bars);
    }

    /**
     * 日K复权（券商软件的"前复权"口径）：以最新一根收盘价为锚，沿 change_pct 往回链式还原
     * 连续价格序列，再用「还原价 ÷ 原始收盘」作因子同乘该根的 open/high/low/close。
     *
     * <p>为什么不直接画库里的原始价：10转10 与现金分红只改写**除权日之后**的基准，之前那几根
     * 存的是当日真实成交价（历史价不该被后来的公司行动改写，这也是 stock_daily_bar 里没有
     * 复权列的原因）。直接画原始价会在除权日凭空跌掉一半，看上去是"腰斩"，而 tooltip 显示的
     * 涨跌幅是按除权后基准算的真实日内涨跌（最多一个跌停）——同一个问题两个答案。
     * change_pct 是收益率、天然对拆股免疫，所以它能当唯一的还原依据，不用改表、不用新数据。
     *
     * <p>边界：最新一根锚定原值（因子恒为 1，图右下角仍是玩家认得的当前价）；收盘价为 0 或
     * 涨跌幅异常（1 + pct &lt;= 0）时该根因子退回 1，绝不拿异常值往前外推。价格 HALF_UP
     * 保留 2 位，与落库口径一致；change_pct 原样透传（复权不改收益率）。
     */
    private static List<KlinePointDTO> forwardAdjusted(List<StockDailyBar> bars) {
        int n = bars.size();
        double[] factor = new double[n];
        if (n > 0) {
            factor[n - 1] = 1.0;
            double adjusted = rawClose(bars.get(n - 1));
            for (int i = n - 2; i >= 0; i--) {
                double rawClose = rawClose(bars.get(i));
                double base = 1.0 + rawChangePct(bars.get(i + 1));
                adjusted = base > 0 ? adjusted / base : rawClose;
                factor[i] = rawClose > 0 ? adjusted / rawClose : 1.0;
            }
        }
        List<KlinePointDTO> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StockDailyBar bar = bars.get(i);
            double f = factor[i];
            points.add(new KlinePointDTO(bar.getGameDay(),
                    Quote.round2(bar.getOpen().doubleValue() * f),
                    Quote.round2(bar.getHigh().doubleValue() * f),
                    Quote.round2(bar.getLow().doubleValue() * f),
                    Quote.round2(rawClose(bar) * f),
                    rawChangePct(bar)));
        }
        return points;
    }

    /** 原始收盘（schema 非空，判空只是防御历史脏行）。 */
    private static double rawClose(StockDailyBar bar) {
        return bar.getClose() == null ? 0.0 : bar.getClose().doubleValue();
    }

    /** 当日涨跌幅（比率），缺失按 0 处理。 */
    private static double rawChangePct(StockDailyBar bar) {
        return bar.getChangePct() == null ? 0.0 : bar.getChangePct();
    }

    /**
     * 单股当日分时点序列（spec §11 GET /api/stocks/{id}/quotes）：固定 90 槽时间轴
     * （上午 09:30-11:30、下午 13:00-15:00 各 45 槽），已走的槽位带价、未到的槽位
     * price=null——前端以此画同花顺式固定时间轴分时图。缓冲来自行情板（cap 90，
     * 重启后从快照价重新积累）；未知 stockId 抛 NOT_FOUND。
     */
    public List<IntradayPointDTO> stockIntraday(long userId, long stockId) {
        requireGameState(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        board.getLock().lock();
        try {
            Quote quote = board.getQuotes().get(stockId);
            if (quote == null) {
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            int firstTick = board.getTickIndex() - quote.getIntraday().size();
            Double[] prices = quote.getIntraday().toArray(new Double[0]);
            List<IntradayPointDTO> points = new ArrayList<>(GameConstants.TOTAL_TICKS);
            List<String> labels = axisLabels();
            for (int tick = 0; tick < GameConstants.TOTAL_TICKS; tick++) {
                Double price = tick >= firstTick && tick < board.getTickIndex()
                        ? prices[tick - firstTick] : null;
                points.add(new IntradayPointDTO(labels.get(tick), price));
            }
            return points;
        } finally {
            board.getLock().unlock();
        }
    }

    /**
     * 百年指数当日分时（spec §11 GET /api/market/index）：固定 90 槽时间轴，规则同单股分时。
     */
    public List<IndexPointDTO> indexIntraday(long userId) {
        requireGameState(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        board.getLock().lock();
        try {
            int firstTick = board.getTickIndex() - board.getIndexIntraday().size();
            Double[] values = board.getIndexIntraday().toArray(new Double[0]);
            List<IndexPointDTO> points = new ArrayList<>(GameConstants.TOTAL_TICKS);
            List<String> labels = axisLabels();
            for (int tick = 0; tick < GameConstants.TOTAL_TICKS; tick++) {
                Double value = tick >= firstTick && tick < board.getTickIndex()
                        ? values[tick - firstTick] : null;
                points.add(new IndexPointDTO(labels.get(tick), value));
            }
            return points;
        } finally {
            board.getLock().unlock();
        }
    }

    /** 固定 90 槽时间轴标签（每次调用重新生成，避免共享可变状态）。 */
    private static List<String> axisLabels() {
        List<String> labels = new ArrayList<>(GameConstants.TOTAL_TICKS);
        for (int tick = 0; tick < GameConstants.TOTAL_TICKS; tick++) {
            labels.add(tickLabel(tick));
        }
        return labels;
    }

    /** 跳数→盘中时刻标签：上午 09:30-11:30、下午 13:00-15:00 各 45 槽，
     *  首/末槽精确对齐 09:30/11:30/13:00/15:00（分母取 TICKS_PER_SESSION-1）。 */
    private static String tickLabel(int tick) {
        boolean morning = tick < GameConstants.TICKS_PER_SESSION;
        int sessionTick = morning ? tick : tick - GameConstants.TICKS_PER_SESSION;
        int offset = Math.round(sessionTick * 120f / (GameConstants.TICKS_PER_SESSION - 1));
        int base = morning ? 9 * 60 + 30 : 13 * 60;
        int minutes = base + offset;
        return "%02d:%02d".formatted(minutes / 60, minutes % 60);
    }

    /** 玩梗评语：按超额收益分档，WorldRandom.of(seed, "comment"+day) 确定性抽取一条。 */
    private String comment(long worldSeed, int day, double excessReturnPct) {
        List<String> pool;
        if (excessReturnPct >= 3.0) {
            pool = BIG_WIN_COMMENTS;
        } else if (excessReturnPct >= 0.0) {
            pool = WIN_COMMENTS;
        } else if (excessReturnPct > -3.0) {
            pool = LOSS_COMMENTS;
        } else {
            pool = BIG_LOSS_COMMENTS;
        }
        Random rng = WorldRandom.of(worldSeed, "comment" + day);
        return pool.get(rng.nextInt(pool.size()));
    }

    /** 最近结算日快照（max game_day），无则 null。 */
    private UserDailySnapshot latestSnapshot(long userId) {
        List<UserDailySnapshot> snapshots = userDailySnapshotMapper.selectList(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .orderByDesc(UserDailySnapshot::getGameDay));
        return snapshots.isEmpty() ? null : snapshots.get(0);
    }

    /** 指定交易日快照，无则 null。 */
    private UserDailySnapshot snapshotAt(long userId, int gameDay) {
        return userDailySnapshotMapper.selectOne(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, userId)
                .eq(UserDailySnapshot::getGameDay, gameDay));
    }

    /** 当日该用户消息，按落库序（id 升序）。 */
    private List<UserMessage> messagesOfDay(long userId, int day) {
        return userMessageMapper.selectList(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getGameDay, day)
                .orderByAsc(UserMessage::getId));
    }

    /**
     * 当前跳号以内存行情板为准（P9 内存权威）：user_game_state.tick_index 每 5 跳才落一次库，
     * 用它做倒计时会让前端"停一下、然后突然跳好几秒"。板子未缓存（离线/刚重启）时退回库值。
     */
    private int liveTickIndex(long userId, UserGameState gs) {
        QuoteBoard board = quoteBoardRegistry.peek(userId);
        return board != null ? board.getTickIndex() : gs.getTickIndex();
    }

    /**
     * 本会话剩余跳数：早盘 = 45 - tickIndex，午盘 = 90 - tickIndex（tickIndex 是全天
     * 绝对跳号）；非交易态返回 0，越界一律夹到 0，绝不返回负数。
     */
    static int remainingTicksInSession(MarketState state, int tickIndex) {
        if (state == MarketState.MORNING_OPEN) {
            return Math.max(0, GameConstants.TICKS_PER_SESSION - tickIndex);
        }
        if (state == MarketState.AFTERNOON_OPEN) {
            return Math.max(0, GameConstants.TOTAL_TICKS - tickIndex);
        }
        return 0; // 未开盘/午休/结算/收盘/终局：没有"本会话剩余跳数"可言
    }

    /** 查询 day 所属纪元（user_era 行 start_day <= day <= end_day），无则 null。 */
    private UserEra eraForDay(long userId, int day) {
        List<UserEra> eras = userEraMapper.selectList(new LambdaQueryWrapper<UserEra>()
                .eq(UserEra::getUserId, userId)
                .orderByAsc(UserEra::getEraIndex));
        for (UserEra era : eras) {
            if (era.getStartDay() <= day && day <= era.getEndDay()) {
                return era;
            }
        }
        return null;
    }
}
