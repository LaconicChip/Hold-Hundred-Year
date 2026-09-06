package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.model.dto.DailySummaryDTO;
import com.holdhunderdyear.game.model.dto.EquityCurveDTO;
import com.holdhunderdyear.game.model.dto.LeaderboardDTO;
import com.holdhunderdyear.game.model.dto.MarketStateDTO;
import com.holdhunderdyear.game.model.enums.EraCode;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.world.WorldRandom;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public MarketQueryService(UserGameStateMapper userGameStateMapper,
                              UserEraMapper userEraMapper,
                              UserDailySnapshotMapper userDailySnapshotMapper,
                              UserMessageMapper userMessageMapper) {
        this.userGameStateMapper = userGameStateMapper;
        this.userEraMapper = userEraMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.userMessageMapper = userMessageMapper;
    }

    /**
     * 市场状态聚合：状态机值、年/年内日换算、当日纪元、段位、盘中打点与倒计时
     * （盘中 = TOTAL_TICKS - tickIndex，其余状态 0）、世界种子。无 game_state 抛 NOT_FOUND。
     */
    public MarketStateDTO state(long userId) {
        UserGameState gameState = requireGameState(userId);
        int currentDay = gameState.getCurrentDay();

        MarketStateDTO dto = new MarketStateDTO();
        dto.setMarketState(gameState.getMarketState());
        dto.setCurrentDay(currentDay);
        dto.setYear((currentDay - 1) / GameConstants.DAYS_PER_YEAR + 1);
        dto.setDayInYear((currentDay - 1) % GameConstants.DAYS_PER_YEAR + 1);
        UserEra era = eraForDay(userId, currentDay);
        if (era != null) {
            dto.setEraCode(era.getEraCode());
            dto.setEraName(EraCode.valueOf(era.getEraCode()).getName());
        }
        dto.setTier(gameState.getTier());
        dto.setTickIndex(gameState.getTickIndex());
        MarketState marketState = MarketState.valueOf(gameState.getMarketState());
        boolean trading = marketState == MarketState.MORNING_OPEN || marketState == MarketState.AFTERNOON_OPEN;
        dto.setCountdownTicks(trading ? GameConstants.TOTAL_TICKS - gameState.getTickIndex() : 0);
        dto.setWorldSeed(gameState.getWorldSeed());
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
        dto.setDebt(snapshot.getDebt().doubleValue());
        dto.setNetAsset(snapshot.getNetAsset().doubleValue());
        double debt = dto.getDebt();
        dto.setGuardRatio(debt > 0 ? (dto.getCash() + dto.getMarketValue()) / debt : 0.0);

        List<UserMessage> messages = messagesOfDay(userId, day);
        dto.setNews(messages.stream()
                .filter(message -> NEWS_SOURCES.contains(message.getSource())
                        && "EVENT".equals(message.getType()))
                .map(UserMessage::getTitle)
                .toList());
        dto.setNotices(messages.stream()
                .filter(message -> "SYSTEM".equals(message.getSource())
                        && "NOTIFY".equals(message.getType()))
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
