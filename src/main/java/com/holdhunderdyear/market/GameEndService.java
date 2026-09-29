package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.event.EventFactory;
import com.holdhunderdyear.event.NpcService;
import com.holdhunderdyear.game.constants.GameConstants;
import com.holdhunderdyear.game.model.dto.GameResultDTO;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.market.entity.GameEvent;
import com.holdhunderdyear.market.entity.LoanRecord;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.TradeRecord;
import com.holdhunderdyear.market.entity.UserDailySnapshot;
import com.holdhunderdyear.market.entity.UserEra;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserIpoRecord;
import com.holdhunderdyear.market.entity.UserMessage;
import com.holdhunderdyear.market.entity.UserPosition;
import com.holdhunderdyear.market.entity.UserScriptDay;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.entity.UserTitle;
import com.holdhunderdyear.market.mapper.GameEventMapper;
import com.holdhunderdyear.market.mapper.LoanRecordMapper;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.TradeRecordMapper;
import com.holdhunderdyear.market.mapper.UserDailySnapshotMapper;
import com.holdhunderdyear.market.mapper.UserEraMapper;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserIpoRecordMapper;
import com.holdhunderdyear.market.mapper.UserMessageMapper;
import com.holdhunderdyear.market.mapper.UserPositionMapper;
import com.holdhunderdyear.market.mapper.UserScriptDayMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.mapper.UserTitleMapper;
import com.holdhunderdyear.trade.CorporateActionService;
import com.holdhunderdyear.world.WorldSeeder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 终局结算页与重开（Phase 7.2，spec §12 /api/game/result 与 /api/game/restart）。
 * result 只读无锁：终局快照（game_day = endDay 的 user_daily_snapshot）为唯一数据源，
 * 排行榜 NPC 行解析自其 npc_assets_json，玩家行以终局净资产追加、按资产降序。
 * restart 采用与 MarginService.borrow 相同的世界锁 + TransactionTemplate 锁内提交
 * 协议：事务内校验终局、逐表清空用户游戏数据、重置账户与 game_state（含 null 字段
 * 须用 LambdaUpdateWrapper 显式 set，updateById 会跳过 null）、以新种子重播种世界；
 * 行情板缓存在事务提交后、锁内 evict，下次访问从重置后的落库行重建。
 */
@Service
public class GameEndService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final UserGameStateMapper userGameStateMapper;
    private final SysUserMapper sysUserMapper;
    private final UserPositionMapper userPositionMapper;
    private final UserStockStateMapper userStockStateMapper;
    private final UserDailySnapshotMapper userDailySnapshotMapper;
    private final UserMessageMapper userMessageMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final UserEraMapper userEraMapper;
    private final UserScriptDayMapper userScriptDayMapper;
    private final UserIpoRecordMapper userIpoRecordMapper;
    private final UserTitleMapper userTitleMapper;
    private final GameEventMapper gameEventMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;
    private final WorldSeeder worldSeeder;
    private final EventFactory eventFactory;
    private final NpcService npcService;
    private final CorporateActionService corporateActionService;
    private final TransactionTemplate transactionTemplate;

    public GameEndService(UserGameStateMapper userGameStateMapper,
                          SysUserMapper sysUserMapper,
                          UserPositionMapper userPositionMapper,
                          UserStockStateMapper userStockStateMapper,
                          UserDailySnapshotMapper userDailySnapshotMapper,
                          UserMessageMapper userMessageMapper,
                          LoanRecordMapper loanRecordMapper,
                          TradeRecordMapper tradeRecordMapper,
                          UserEraMapper userEraMapper,
                          UserScriptDayMapper userScriptDayMapper,
                          UserIpoRecordMapper userIpoRecordMapper,
                          UserTitleMapper userTitleMapper,
                          GameEventMapper gameEventMapper,
                          QuoteBoardRegistry quoteBoardRegistry,
                          WorldSeeder worldSeeder,
                          EventFactory eventFactory,
                          NpcService npcService,
                          CorporateActionService corporateActionService,
                          PlatformTransactionManager transactionManager) {
        this.userGameStateMapper = userGameStateMapper;
        this.sysUserMapper = sysUserMapper;
        this.userPositionMapper = userPositionMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.userMessageMapper = userMessageMapper;
        this.loanRecordMapper = loanRecordMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.userEraMapper = userEraMapper;
        this.userScriptDayMapper = userScriptDayMapper;
        this.userIpoRecordMapper = userIpoRecordMapper;
        this.userTitleMapper = userTitleMapper;
        this.gameEventMapper = gameEventMapper;
        this.quoteBoardRegistry = quoteBoardRegistry;
        this.worldSeeder = worldSeeder;
        this.eventFactory = eventFactory;
        this.npcService = npcService;
        this.corporateActionService = corporateActionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 终局结算页数据：结局类型、存活天数、总收益率（对初始资金 10 万）、指数涨幅
     * （对基期 1000 点）、最终段位、强平流水、本局种子号、0..endDay 资产 vs 指数曲线、
     * 最终排行榜。非终局抛 GAME_NOT_FINISHED，无 game_state 抛 NOT_FOUND。
     */
    public GameResultDTO result(long userId) {
        UserGameState gs = gameState(userId);
        MarketState state = MarketState.valueOf(gs.getMarketState());
        if (state != MarketState.FINISHED && state != MarketState.BANKRUPT) {
            throw new BizException(ErrorCode.GAME_NOT_FINISHED);
        }
        UserDailySnapshot finalSnapshot = userDailySnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .eq(UserDailySnapshot::getGameDay, gs.getEndDay()));

        GameResultDTO result = new GameResultDTO();
        result.setEnding(state.name());
        result.setEndReason(gs.getEndReason());
        result.setEndDay(gs.getEndDay());
        result.setTotalReturnPct((finalSnapshot.getNetAsset().doubleValue()
                / GameConstants.INITIAL_BALANCE - 1) * 100);
        result.setIndexGainPct((finalSnapshot.getIndexValue() / GameConstants.INITIAL_INDEX - 1) * 100);
        result.setFinalTier(gs.getTier());
        result.setFinalTierName(SettlementService.tierNameOf(gs.getTier()));
        result.setWorldSeed(String.valueOf(gs.getWorldSeed()));
        result.setLiquidations(liquidations(userId));
        result.setCurve(curve(userId, gs.getEndDay()));
        result.setRankings(rankings(state, finalSnapshot));
        return result;
    }

    /**
     * 终局重开：世界锁 + 锁内事务提交后 evict 行情板缓存。事务内校验终局（非终局抛
     * GAME_NOT_FINISHED），按 userId 逐表清空游戏数据（持仓/股票状态/快照/消息/
     * 融资流水/成交记录/纪元/剧本日/打新记录/称号/事件），重置账户为初始资金 10 万、
     * 负债 0，game_state 全字段重置回第 1 天 NOT_STARTED（null 字段显式 set），
     * 最后以新种子重新播种世界。账号与密码保留。
     */
    public void restart(long userId) {
        quoteBoardRegistry.worldLock(userId).lock();
        try {
            transactionTemplate.executeWithoutResult(status -> doRestart(userId));
            quoteBoardRegistry.evict(userId);
        } finally {
            quoteBoardRegistry.worldLock(userId).unlock();
        }
    }

    /** restart 事务体（世界锁内开启，提交先于锁释放与 evict）。 */
    private void doRestart(long userId) {
        UserGameState gs = gameState(userId);
        MarketState state = MarketState.valueOf(gs.getMarketState());
        if (state != MarketState.FINISHED && state != MarketState.BANKRUPT) {
            throw new BizException(ErrorCode.GAME_NOT_FINISHED);
        }
        long newSeed = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

        userPositionMapper.delete(new LambdaQueryWrapper<UserPosition>()
                .eq(UserPosition::getUserId, userId));
        userStockStateMapper.delete(new LambdaQueryWrapper<UserStockState>()
                .eq(UserStockState::getUserId, userId));
        userDailySnapshotMapper.delete(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, userId));
        userMessageMapper.delete(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId));
        loanRecordMapper.delete(new LambdaQueryWrapper<LoanRecord>()
                .eq(LoanRecord::getUserId, userId));
        tradeRecordMapper.delete(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId));
        userEraMapper.delete(new LambdaQueryWrapper<UserEra>()
                .eq(UserEra::getUserId, userId));
        userScriptDayMapper.delete(new LambdaQueryWrapper<UserScriptDay>()
                .eq(UserScriptDay::getUserId, userId));
        userIpoRecordMapper.delete(new LambdaQueryWrapper<UserIpoRecord>()
                .eq(UserIpoRecord::getUserId, userId));
        userTitleMapper.delete(new LambdaQueryWrapper<UserTitle>()
                .eq(UserTitle::getUserId, userId));
        gameEventMapper.delete(new LambdaQueryWrapper<GameEvent>()
                .eq(GameEvent::getUserId, userId));
        eventFactory.clearPending(userId);
        npcService.clearUserState(userId);
        corporateActionService.clearUserState(userId);

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        user.setBalance(BigDecimal.valueOf(GameConstants.INITIAL_BALANCE));
        user.setDebt(BigDecimal.valueOf(0.00));
        sysUserMapper.updateById(user);

        // MyBatis-Plus updateById 跳过 null 字段，endReason/endDay/prevTotalAsset 必须显式 set null
        userGameStateMapper.update(null, new LambdaUpdateWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId)
                .set(UserGameState::getCurrentDay, 1)
                .set(UserGameState::getMarketState, "NOT_STARTED")
                .set(UserGameState::getTickIndex, 0)
                .set(UserGameState::getWorldSeed, newSeed)
                .set(UserGameState::getIndexValue, 1000.0)
                .set(UserGameState::getPrevIndex, 1000.0)
                .set(UserGameState::getPrevTotalAsset, null)
                .set(UserGameState::getVolRegime, "NORMAL")
                .set(UserGameState::getTier, "RETAIL")
                .set(UserGameState::getEndReason, null)
                .set(UserGameState::getEndDay, null));

        worldSeeder.seedWorld(userId, newSeed);
    }

    /** 强平流水：loan_type=LIQUIDATE，按 gameDay,id 升序。 */
    private List<LoanRecord> liquidations(long userId) {
        return loanRecordMapper.selectList(new LambdaQueryWrapper<LoanRecord>()
                .eq(LoanRecord::getUserId, userId)
                .eq(LoanRecord::getLoanType, "LIQUIDATE")
                .orderByAsc(LoanRecord::getGameDay)
                .orderByAsc(LoanRecord::getId));
    }

    /** 曲线：game_day 0..endDay 快照升序映射为净资产 vs 指数点。 */
    private List<GameResultDTO.CurvePoint> curve(long userId, int endDay) {
        List<UserDailySnapshot> snapshots = userDailySnapshotMapper.selectList(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, userId)
                        .le(UserDailySnapshot::getGameDay, endDay)
                        .orderByAsc(UserDailySnapshot::getGameDay));
        List<GameResultDTO.CurvePoint> curve = new ArrayList<>(snapshots.size());
        for (UserDailySnapshot snapshot : snapshots) {
            GameResultDTO.CurvePoint point = new GameResultDTO.CurvePoint();
            point.setGameDay(snapshot.getGameDay());
            point.setNetAsset(snapshot.getNetAsset().doubleValue());
            point.setIndexValue(snapshot.getIndexValue());
            curve.add(point);
        }
        return curve;
    }

    /** 最终排行榜：终局快照 npc_assets_json 的 8 个 NPC 行 + 玩家行（终局净资产，
     * 爆仓结局 status=BANKRUPT 否则 ACTIVE），按资产降序。 */
    private List<GameResultDTO.RankRow> rankings(MarketState state, UserDailySnapshot finalSnapshot) {
        List<GameResultDTO.RankRow> rankings = new ArrayList<>();
        String npcJson = finalSnapshot.getNpcAssetsJson();
        if (npcJson != null && !npcJson.isBlank()) {
            JsonNode npcs = MAPPER.readTree(npcJson);
            for (int i = 0; i < npcs.size(); i++) {
                JsonNode npc = npcs.get(i);
                GameResultDTO.RankRow row = new GameResultDTO.RankRow();
                row.setName(npc.get("name").asString());
                row.setAsset(npc.get("asset").asDouble());
                row.setStatus(npc.get("status").asString());
                row.setPlayer(false);
                rankings.add(row);
            }
        }
        GameResultDTO.RankRow player = new GameResultDTO.RankRow();
        player.setName("您");
        player.setAsset(finalSnapshot.getNetAsset().doubleValue());
        player.setStatus(state == MarketState.BANKRUPT ? "BANKRUPT" : "ACTIVE");
        player.setPlayer(true);
        rankings.add(player);
        rankings.sort(Comparator.comparing(GameResultDTO.RankRow::getAsset).reversed());
        return rankings;
    }

    /** 读取用户世界状态，缺失抛 NOT_FOUND。 */
    private UserGameState gameState(long userId) {
        UserGameState gs = userGameStateMapper.selectOne(
                new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, userId));
        if (gs == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return gs;
    }
}
