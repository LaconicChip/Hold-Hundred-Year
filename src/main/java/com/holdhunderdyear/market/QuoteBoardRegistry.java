package com.holdhunderdyear.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.market.entity.UserGameState;
import com.holdhunderdyear.market.entity.UserStockState;
import com.holdhunderdyear.market.mapper.UserGameStateMapper;
import com.holdhunderdyear.market.mapper.UserStockStateMapper;
import com.holdhunderdyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;

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
            return QuoteBoard.restore(states, gameState.getIndexValue(), gameState.getTickIndex());
        });
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
