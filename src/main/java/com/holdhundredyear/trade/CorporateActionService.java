package com.holdhundredyear.trade;

import com.holdhundredyear.event.NotifyPublisher;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserStockState;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.mapper.UserStockStateMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业行动服务（Task 9.1，spec §6.5 A 股特色机制）：由 {@link
 * com.holdhundredyear.event.EventFactory} 在掷出 fund- 企业行动类骨架时调用，对给定
 * userId 世界内某只 stockId 做真实的市场机制动作——ST 戴帽/摘帽、10转10 高送转除权、
 * 现金分红除息。ST 的 ±5% 涨跌停收窄由 {@link Quote#limitUpPrice} 按 isSt 即时生效，
 * 本服务只翻转印记并保持行情与落库一致。
 *
 * <p>并发协议：本类全部方法由事件工厂在 per-user 世界锁内（tickOne 事务体）调用，
 * 不自行加锁；持仓/余额/股票状态读写与结算保持一致的事务可见性。戴帽去重表为进程内
 * 内存态（key=userId|stockId），跨局有效但重开时由 {@link #clearUserState} 清空——
 * 与 MainScriptService/NpcService 的 clearUserState 协议一致。
 *
 * <p>守恒原则：split 使股数/均价与股价同向缩放、分红使现金与市值反向增减，均保持
 * 净资产/市值不变；价格运算走 {@link Quote#round2}（HALF_UP 保留 2 位）。
 */
@Service
public class CorporateActionService {

    /** 每股戴帽次数闸去重键集合（key = userId|stockId）。 */
    private final Set<String> hattedKeys = ConcurrentHashMap.newKeySet();

    private final UserPositionMapper userPositionMapper;
    private final UserStockStateMapper userStockStateMapper;
    private final SysUserMapper sysUserMapper;
    private final NotifyPublisher notifyPublisher;

    public CorporateActionService(UserPositionMapper userPositionMapper,
                                  UserStockStateMapper userStockStateMapper,
                                  SysUserMapper sysUserMapper,
                                  NotifyPublisher notifyPublisher) {
        this.userPositionMapper = userPositionMapper;
        this.userStockStateMapper = userStockStateMapper;
        this.sysUserMapper = sysUserMapper;
        this.notifyPublisher = notifyPublisher;
    }

    /**
     * ST 戴帽：每股每局至多 1 次（内存键去重，已戴返回 false 且不做任何改动）。首次把
     * 行情与落库 is_st 都置 true（Quote 涨跌停价已按 isSt 收窄为 ±5%，无需改价）并写消息。
     *
     * @param gameDay 当日游戏日（消息落库 game_day，schema 非空）
     * @return 本次是否真的完成戴帽（false 表示已戴过）
     */
    public boolean hat(long userId, QuoteBoard board, long stockId, int gameDay) {
        String key = userId + "|" + stockId;
        if (!hattedKeys.add(key)) {
            return false;
        }
        Quote quote = board.quote(stockId);
        quote.setSt(true);
        updateSt(userId, stockId, true);
        notify(userId, stockId, gameDay, "ST 戴帽",
                StockDefs.byId(stockId).name() + "被实施退市风险警示，即日起涨跌停板收窄至 ±5%。");
        return true;
    }

    /** 摘帽：行情与落库 is_st 置 false，涨跌停恢复 ±10%，写消息。 */
    public void unhat(long userId, QuoteBoard board, long stockId, int gameDay) {
        Quote quote = board.quote(stockId);
        quote.setSt(false);
        updateSt(userId, stockId, false);
        notify(userId, stockId, gameDay, "摘帽",
                StockDefs.byId(stockId).name() + "成功撤销退市风险警示，涨跌停板恢复至 ±10%。");
    }

    /**
     * 10转10 高送转除权：该 userId 对 stockId 的持仓股数/可卖股数 ×2、平均成本 ÷2；行情
     * 按 ÷2 整体折算当日口径（见 {@link Quote#rebaseToday}）并同步落库——市值不变、净资产
     * 不变；涨跌停基准按除权后 prevClose 自动重算（Quote.limitUpPrice 即用 prevClose）。写消息。
     */
    public void split10to10(long userId, QuoteBoard board, long stockId, int gameDay) {
        Quote quote = board.quote(stockId);
        quote.rebaseToday(price -> price / 2.0);

        UserPosition position = position(userId, stockId);
        if (position != null) {
            position.setShares(position.getShares() * 2);
            position.setAvailableShares(position.getAvailableShares() * 2);
            position.setAvgCost(position.getAvgCost().divide(TWO, 2, RoundingMode.HALF_UP));
            userPositionMapper.updateById(position);
        }
        persistToday(userId, stockId, quote);
        notify(userId, stockId, gameDay, "10转10 除权",
                StockDefs.byId(stockId).name() + "实施10转10高送转，今日除权，持仓数量翻倍，股价按除权后口径调整。");
    }

    /**
     * 现金分红除息：持仓股数 × 每股分红入账（落 sys_user.balance）；行情按减分红额整体折算
     * 当日口径（见 {@link Quote#rebaseToday}）并同步落库——现金↑、市值↓、净资产守恒。写消息。
     *
     * @param divPerShare 每股分红（元，调用方保证 &gt; 0，如 0.5~2）
     */
    public void cashDividend(long userId, QuoteBoard board, long stockId, double divPerShare, int gameDay) {
        Quote quote = board.quote(stockId);
        BigDecimal div = BigDecimal.valueOf(divPerShare);
        UserPosition position = position(userId, stockId);
        if (position != null && position.getShares() > 0) {
            BigDecimal dividend = div.multiply(BigDecimal.valueOf(position.getShares()))
                    .setScale(2, RoundingMode.HALF_UP);
            SysUser user = sysUserMapper.selectById(userId);
            user.setBalance(user.getBalance().add(dividend));
            sysUserMapper.updateById(user);
        }
        quote.rebaseToday(price -> price - divPerShare);
        persistToday(userId, stockId, quote);
        notify(userId, stockId, gameDay, "现金分红到账",
                StockDefs.byId(stockId).name() + "派发现金红利，持股每股到账 "
                        + div.setScale(2, RoundingMode.HALF_UP).toPlainString() + " 元，股价对应除息。");
    }

    /** 清除该 userId 的"已戴帽"记录（重开调用，与新局的 is_st 落库一致）。 */
    public void clearUserState(long userId) {
        hattedKeys.removeIf(key -> key.startsWith(userId + "|"));
    }

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /**
     * 把行情板里折算后的当日五个价（昨收/开盘/最高/最低/现价）双写进 user_stock_state——
     * 与 {@link com.holdhundredyear.market.WorldTickService} 每跳同步 current/high/low 的口径
     * 一致（结算落日K读的就是这几列），所以必须以行情板为唯一来源，不能只折库里的列。
     * 行缺失则跳过。
     */
    private void persistToday(long userId, long stockId, Quote quote) {
        UserStockState state = stockState(userId, stockId);
        if (state != null) {
            state.setPrevClose(BigDecimal.valueOf(quote.getPrevClose()));
            state.setOpenPrice(BigDecimal.valueOf(quote.getOpen()));
            state.setHighPrice(BigDecimal.valueOf(quote.getHigh()));
            state.setLowPrice(BigDecimal.valueOf(quote.getLow()));
            state.setCurrentPrice(BigDecimal.valueOf(quote.getCurrent()));
            userStockStateMapper.updateById(state);
        }
    }

    /** 落库 is_st 翻转（与内存行情板一致；行缺失则跳过）。 */
    private void updateSt(long userId, long stockId, boolean st) {
        UserStockState state = stockState(userId, stockId);
        if (state != null) {
            state.setIsSt(st);
            userStockStateMapper.updateById(state);
        }
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

    /** 企业行动通知落库（参数顺序沿用本服务习惯，字段惯例统一在 {@link NotifyPublisher}）。 */
    private void notify(long userId, long stockId, int gameDay, String title, String content) {
        notifyPublisher.system(userId, gameDay, title, content, stockId);
    }
}