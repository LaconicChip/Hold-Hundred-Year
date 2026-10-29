package com.holdhundredyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 行情板注册表（P9 内存权威）：userId → {@link QuoteBoard} 的进程内缓存。
 * 首次访问时从 user_stock_state / user_game_state 快照恢复；开盘管线
 * （{@link MarketStateService#startDay}）先 evict 再重载，保证缓存板反映最新落库行。
 *
 * <p>每用户世界锁（P5）也挂在本表：锁不随板实例更替（evict 换板不换锁），
 * 开盘/一跳/交易等世界级写操作须全程持有并"锁内提交"。
 */
@Service
public class QuoteBoardRegistry {

    private final Map<Long, QuoteBoard> boards = new ConcurrentHashMap<>();

    private final Map<Long, ReentrantLock> worldLocks = new ConcurrentHashMap<>();

    private final UserStockStateMapper userStockStateMapper;
    private final UserGameStateMapper userGameStateMapper;

    public QuoteBoardRegistry(UserStockStateMapper userStockStateMapper,
                              UserGameStateMapper userGameStateMapper) {
        this.userStockStateMapper = userStockStateMapper;
        this.userGameStateMapper = userGameStateMapper;
    }

    /** 取该用户的行情板；未缓存则从 stock_state 快照恢复（按 stockId 升序保持 StockDefs 档案顺序）。 */
    public QuoteBoard getOrLoad(long userId) {
        return boards.computeIfAbsent(userId, id -> {
            List<UserStockState> states = userStockStateMapper.selectList(
                    new LambdaQueryWrapper<UserStockState>()
                            .eq(UserStockState::getUserId, id)
                            .orderByAsc(UserStockState::getStockId));
            UserGameState gameState = userGameStateMapper.selectOne(
                    new LambdaQueryWrapper<UserGameState>().eq(UserGameState::getUserId, id));
            // 没有 game_state 就没有这个世界：直接给业务错误，别在 gameState.getIndexValue() 上
            // 抛 NPE 变成 500。便携版最容易撞上——玩家换了包 / 删了 data\ 存档 / 恢复了旧档，
            // 浏览器里还留着上一个用户签发的令牌，于是每个轮询接口都会打进来。
            if (gameState == null) {
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            states = ensureInstrumentRows(id, states);
            QuoteBoard board = QuoteBoard.restore(states, gameState.getIndexValue(),
                    gameState.getPrevIndex(), gameState.getTickIndex());
            // 本局档案（标准/快速）决定涨跌停幅度与跳速，恢复出来就要带上
            board.setGameMode(GameMode.of(gameState.getGameMode()));
            board.applyLimits();
            return board;
        });
    }

    /**
     * 老存档补 ETF 行（2026-10-25）：ETF 上线前创建的存档只有 1..24 行，行情板缺 25~27
     * 会在交易/详情页直接抛"未知 stockId"。这里按当前行情补三行并落库（INSERT IGNORE），
     * 补出来的初始价不是发行价而是<b>按跟踪标的折算的现价</b>——否则老存档一升级，
     * ETF 会凭空多出一个几十个点的"首日涨幅"。
     *
     * <p>只在缺行时才写库；正常存档是一次 size 比较 + 直接返回。
     */
    private List<UserStockState> ensureInstrumentRows(long userId, List<UserStockState> states) {
        if (states.size() >= StockDefs.ALL_INSTRUMENTS.size()) {
            return states;
        }
        Map<Long, UserStockState> byId = new LinkedHashMap<>();
        for (UserStockState state : states) {
            byId.put(state.getStockId(), state);
        }
        boolean added = false;
        for (StockDefs.StockDef def : StockDefs.ETFS) {
            if (byId.containsKey(def.id())) {
                continue;
            }
            double price = def.basePrice();
            UserStockState row = new UserStockState();
            row.setUserId(userId);
            row.setStockId(def.id());
            row.setPrevClose(BigDecimal.valueOf(price));
            row.setOpenPrice(BigDecimal.valueOf(price));
            row.setHighPrice(BigDecimal.valueOf(price));
            row.setLowPrice(BigDecimal.valueOf(price));
            row.setCurrentPrice(BigDecimal.valueOf(price));
            // QuoteBoard.restore 直接读这些字段，内存行必须与库里的默认值一致（null 会 NPE）
            row.setLimitUpStreak(0);
            row.setHalted(false);
            row.setIsSt(false);
            row.setGapPct(0.0);
            row.setVersion(0);
            userStockStateMapper.insertIgnore(row);
            states.add(row);
            byId.put(def.id(), row);
            added = true;
        }
        if (added) {
            states.sort(Comparator.comparing(UserStockState::getStockId));
        }
        return states;
    }

    /** 只读已缓存的行情板；没有缓存返回 null（绝不顺手查库加载，供高频心跳做廉价预判）。 */
    public QuoteBoard peek(long userId) {
        return boards.get(userId);
    }

    /** 丢弃缓存板（开盘前强制重载 / 重开 / 登出清理用）。 */
    public void evict(long userId) {
        boards.remove(userId);
    }

    /** 每用户世界锁（P5）：evict 换板不换锁；调用方负责全程持有并锁内提交。 */
    public ReentrantLock worldLock(long userId) {
        return worldLocks.computeIfAbsent(userId, id -> new ReentrantLock());
    }
}
