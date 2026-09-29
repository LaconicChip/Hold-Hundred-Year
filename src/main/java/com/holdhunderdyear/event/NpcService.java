package com.holdhunderdyear.event;

import com.holdhunderdyear.game.constants.NpcDefs;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.content.NpcLines;
import com.holdhunderdyear.game.model.enums.EraCode;
import com.holdhunderdyear.game.model.enums.SealState;
import com.holdhunderdyear.game.model.enums.VolRegime;
import com.holdhunderdyear.market.engine.PriceShock;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.TradeRecord;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.TradeRecordMapper;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import com.holdhunderdyear.world.WorldRandom;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 行情服务（Task 8.4，spec §7.7）：每日收盘时为当日快照生成 NPC 资产 JSON
 * （收益 = beta×rMkt + 风格漂移 + seeded 噪声，可复现）+ 触发 NPC 角色线消息。
 * 消息统一来源 "NPC"、type "NOTIFY"，经 user_message 落库。
 *
 * <p>由 {@link com.holdhunderdyear.market.SettlementService#settle} 在每个 insertSnapshot
 * 前调用；本方法只返回 JSON 字符串并顺带写角色线消息，快照落库由调用方负责。
 *
 * <p>@玩家事件每日至多 1 条，用进程内 Set(userId,day) 去重，{@link #clearUserState} 供重开清理。
 */
@Service
public class NpcService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 张大妈荐股每日触发概率（其余天不荐股，防每日常发）。 */
    private static final double CHATTER_PROB = 0.45;
    private static final double DAMA_RECO_PROB = 0.85;

    /** 满仓判定：玩家现金 ≤ 该阈值视为基本全部买入（spec §7.7 老李停车场）。 */
    private static final double FULL_POSITION_CASH = 1000.0;

    /** 张大妈反指走弱介入的负向幅度。 */
    private static final double DAMA_DECLINE_PCT = -0.05;

    private final UserMessageMapper userMessageMapper;
    private final UserDailySnapshotMapper userDailySnapshotMapper;
    private final SysUserMapper sysUserMapper;
    private final TradeRecordMapper tradeRecordMapper;

    /** @玩家消息每日去重：userId → 已发消息的 gameDay。 */
    private final Map<Long, Set<Integer>> playerMsgDays = new ConcurrentHashMap<>();

    public NpcService(UserMessageMapper userMessageMapper,
                      UserDailySnapshotMapper userDailySnapshotMapper,
                      SysUserMapper sysUserMapper,
                      TradeRecordMapper tradeRecordMapper) {
        this.userMessageMapper = userMessageMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.sysUserMapper = sysUserMapper;
        this.tradeRecordMapper = tradeRecordMapper;
    }

    /**
     * 生成当日 NPC 资产 JSON（顺序=NpcDefs.ALL）并顺带触发本日角色线消息。
     * 前一交易日（day-1）快照 npc_assets_json 缺失时按基期（每 code asset=100000、ACTIVE）。
     * 黑7月中 withdrawInBlackJuly 的 NPC 资产定格在上一次值并标记状态，其余正常演进。
     * 只返回 JSON 字符串，不落库（落库由 SettlementService.insertSnapshot 负责）。
     */
    public String dailyAssetsJson(long userId, int day, UserGameState gs, QuoteBoard board, double rMkt) {
        Random rng = WorldRandom.of(gs.getWorldSeed(), "npc" + day);
        Map<String, Double> prev = readPrevAssets(userId, day);
        boolean blackJuly = board.getCurrentEra() != null
                && board.getCurrentEra().code() == EraCode.BLACK_JULY;

        List<Map<String, Object>> npcs = new ArrayList<>(NpcDefs.ALL.size());
        for (NpcDefs.Npc npc : NpcDefs.ALL) {
            NpcDefs.NpcConfig config = NpcDefs.CONFIG.get(npc.code());
            double prevAsset = prev.getOrDefault(npc.code(), 100000.0);
            double asset;
            String status;
            if (config.withdrawInBlackJuly() && blackJuly) {
                // 黑7月销户：资产定格在上一次值，状态标记为销户/排队
                asset = prevAsset;
                status = blackJulyWithdraw(npc.code(), true);
            } else {
                asset = nextAsset(prevAsset, config.beta(), config.driftMean(),
                        config.styleVol(), rMkt, rng);
                status = "ACTIVE";
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", npc.code());
            entry.put("name", npc.name());
            entry.put("asset", asset);
            entry.put("status", status);
            npcs.add(entry);
        }

        maybeDamaRecommends(userId, day, board, rng);
        maybeLaoliParkingWarn(userId, day, board, rng);
        maybePlayerEvents(userId, day, board, rng);
        maybeNpcChatter(userId, day, npcs, rng);
        return MAPPER.writeValueAsString(npcs);
    }

    /** 重开/登出时清除该 userId 的 @玩家消息当日去重记录。 */
    public void clearUserState(long userId) {
        playerMsgDays.remove(userId);
    }

    // ---- 静态纯函数（供统计测试）----

    /** 单日日收益模型：prev × (1 + beta·rMkt + driftMean + styleVol·nextGaussian)。 */
    static double nextAsset(double prev, double beta, double driftMean, double styleVol,
                            double rMkt, Random rng) {
        return prev * (1 + beta * rMkt + driftMean + styleVol * rng.nextGaussian());
    }

    /** 张大妈荐股反指：65% 概率后续走弱。 */
    static boolean damaDeclines(Random rng) {
        return rng.nextDouble() < 0.65;
    }

    /** 老李停车场：满仓且波动体制 WILD 时命中。 */
    static boolean laoliParkingTrigger(boolean fullPosition, VolRegime regime) {
        return fullPosition && regime == VolRegime.WILD;
    }

    /** 黑7月销户状态："ACTIVE"（不打扰）或 "天台排队中"/"已销户"。 */
    static String blackJulyWithdraw(String code, boolean blackJuly) {
        if (!blackJuly) {
            return "ACTIVE";
        }
        NpcDefs.NpcConfig config = NpcDefs.CONFIG.get(code);
        if (config == null || !config.withdrawInBlackJuly()) {
            return "ACTIVE";
        }
        return "LAOWANG".equals(code) ? "天台排队中" : "已销户";
    }

    // ---- 角色线 ----

    /** 张大妈荐股：每日掷门（防每日常发），挑一只非停牌股荐股并写通知；再掷反指，命中则
     *  对该股入一个负向 ONCE 冲击表达走弱。 */
    private void maybeDamaRecommends(long userId, int day, QuoteBoard board, Random rng) {
        if (rng.nextDouble() >= DAMA_RECO_PROB) {
            return;
        }
        List<Long> tradable = new ArrayList<>();
        for (Quote quote : board.getQuotes().values()) {
            if (!quote.isHalted()) {
                tradable.add(quote.getStockId());
            }
        }
        if (tradable.isEmpty()) {
            return;
        }
        long stockId = tradable.get(rng.nextInt(tradable.size()));
        String stockName = StockDefs.byId(stockId).name();
        notify(userId, day, "张大妈荐股",
                pick(NpcLines.Scene.DAMA_RECOMMEND, rng).replace("{stock}", stockName),
                stockId);
        if (damaDeclines(rng)) {
            board.getShocks().enqueue(new PriceShock(stockId, false,
                    DAMA_DECLINE_PCT, 1, PriceShock.Decay.ONCE));
        }
    }

    /** 老李停车场：满仓且 WILD 时写一条停车位警示通知（台词从池里抽）。 */
    private void maybeLaoliParkingWarn(long userId, int day, QuoteBoard board, Random rng) {
        SysUser user = sysUserMapper.selectById(userId);
        boolean fullPosition = user != null && isFullPosition(user);
        if (laoliParkingTrigger(fullPosition, board.getVolRegime())) {
            notify(userId, day, "老李停车场", pick(NpcLines.Scene.LAOLI_PARKING, rng), null);
        }
    }

    /** 满仓判定：现金基本归零视为全部买入。 */
    private static boolean isFullPosition(SysUser user) {
        return user.getBalance() != null
                && user.getBalance().doubleValue() <= FULL_POSITION_CASH;
    }

    /** @玩家事件（每日至多 1 条）：追涨停买入当日收盘 → 龙虎榜；跌停割肉次日涨停 → 割肉。 */
    private void maybePlayerEvents(long userId, int day, QuoteBoard board, Random rng) {
        for (TradeRecord record : trades(userId, day, "BUY")) {
            if (closedAtLimitUp(board, record.getStockId())) {
                if (claimPlayerMsg(userId, day)) {
                    notify(userId, day, "龙虎榜",
                            pick(NpcLines.Scene.LONGBOARD, rng), record.getStockId());
                }
                return;
            }
        }
        for (TradeRecord record : trades(userId, day - 1, "SELL")) {
            if (closedAtLimitUp(board, record.getStockId())) {
                if (claimPlayerMsg(userId, day)) {
                    notify(userId, day, "割肉", pick(NpcLines.Scene.CUT_LOSS, rng), record.getStockId());
                }
                return;
            }
        }
    }

    /**
     * 每日 NPC 闲聊（约 45% 概率）：从当日仍在场的 ACTIVE NPC 里挑一位说一句性格台词，
     * 让排行榜那几张老面孔真的贯穿全剧，而不是只在结算时冒出一个数字。
     */
    private void maybeNpcChatter(long userId, int day, List<Map<String, Object>> npcs, Random rng) {
        if (rng.nextDouble() >= CHATTER_PROB) {
            return;
        }
        List<Integer> active = new ArrayList<>();
        for (int i = 0; i < npcs.size(); i++) {
            if ("ACTIVE".equals(npcs.get(i).get("status"))) {
                active.add(i);
            }
        }
        if (active.isEmpty()) {
            return;
        }
        Map<String, Object> npc = npcs.get(active.get(rng.nextInt(active.size())));
        String code = String.valueOf(npc.get("code"));
        String name = String.valueOf(npc.get("name"));
        List<String> lines = NpcLines.chatter(code);
        if (lines.isEmpty()) {
            return;
        }
        notify(userId, day, name + "的今日语录", lines.get(rng.nextInt(lines.size())), null);
    }

    /** 从场景台词池抽一条。 */
    private static String pick(NpcLines.Scene scene, Random rng) {
        List<String> lines = NpcLines.scene(scene);
        return lines.get(rng.nextInt(lines.size()));
    }

    /** 该 user 某日某方向的成交记录。 */
    private List<TradeRecord> trades(long userId, int gameDay, String tradeType) {
        return tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId)
                .eq(TradeRecord::getGameDay, gameDay)
                .eq(TradeRecord::getTradeType, tradeType));
    }

    /** 收于涨停判定：结算时 prevClose 已被 settleStocks 覆盖为收盘价，!canBuy() 失真，
     *  以封板状态机维护的 sealed=UP 作为"收涨停"权威信号。 */
    private static boolean closedAtLimitUp(QuoteBoard board, long stockId) {
        return board.quote(stockId).getSealed() == SealState.UP;
    }

    /** @玩家消息当日占位：返回 true 表示本日首次（放行），false 表示本日已发（去重）。 */
    private boolean claimPlayerMsg(long userId, int day) {
        return playerMsgDays.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(day);
    }

    // ---- 数据读取 ----

    /** 读前一交易日（day-1）快照的 NPC 资产 JSON，解析为 code → asset；缺失返回空 Map。 */
    private Map<String, Double> readPrevAssets(long userId, int day) {
        UserDailySnapshot prev = userDailySnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .eq(UserDailySnapshot::getGameDay, day - 1));
        Map<String, Double> assets = new LinkedHashMap<>();
        if (prev == null || prev.getNpcAssetsJson() == null || prev.getNpcAssetsJson().isBlank()) {
            return assets;
        }
        JsonNode arr = MAPPER.readTree(prev.getNpcAssetsJson());
        for (JsonNode node : arr) {
            assets.put(node.get("code").asString(), node.get("asset").asDouble());
        }
        return assets;
    }

    /** NPC 角色线消息落库（字段惯例：来源 "NPC"、type "NOTIFY"、未读、当日）。 */
    private void notify(long userId, int day, String title, String content, Long stockId) {
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setSource("NPC");
        message.setType("NOTIFY");
        message.setTitle(title);
        message.setContent(content);
        message.setStockId(stockId);
        message.setIsRead(false);
        message.setGameDay(day);
        userMessageMapper.insert(message);
    }
}