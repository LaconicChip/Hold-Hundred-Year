package com.holdhunderdyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.common.PageResult;
import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.dto.AssetsDTO;
import com.holdhunderdyear.game.model.dto.PositionDTO;
import com.holdhunderdyear.game.model.dto.TitleDTO;
import com.holdhunderdyear.game.model.dto.TradeItemDTO;
import com.holdhunderdyear.market.QuoteBoardRegistry;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.TradeRecord;
import com.holdhunderdyear.market.entity.UserPosition;
import com.holdhunderdyear.market.entity.UserTitle;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.TradeRecordMapper;
import com.holdhunderdyear.market.mapper.UserPositionMapper;
import com.holdhunderdyear.market.mapper.UserTitleMapper;
import com.holdhunderdyear.market.quote.Quote;
import com.holdhunderdyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人资产/持仓/流水/称号查询聚合（spec §11 GET /api/me/{assets,positions,trades,titles}）。
 * 现价一律读本账户行情板（世界锁内快照读取，mark-to-market 与交易同源）；无 game_state
 * 抛 NOT_FOUND。
 */
@Service
public class MeQueryService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int TRADE_PAGE_SIZE = 100;

    private final SysUserMapper sysUserMapper;
    private final UserPositionMapper userPositionMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final UserTitleMapper userTitleMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;

    public MeQueryService(SysUserMapper sysUserMapper,
                          UserPositionMapper userPositionMapper,
                          TradeRecordMapper tradeRecordMapper,
                          UserTitleMapper userTitleMapper,
                          QuoteBoardRegistry quoteBoardRegistry) {
        this.sysUserMapper = sysUserMapper;
        this.userPositionMapper = userPositionMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.userTitleMapper = userTitleMapper;
        this.quoteBoardRegistry = quoteBoardRegistry;
    }

    /**
     * 资产看板（spec §6.2）：现金 = balance、市值 = Σ(现价×股数)（停牌按停牌前价）、
     * 净资产 = 现金 + 市值 − 负债、担保比例 = (现金+市值)÷负债（无负债固定 0）。
     */
    public AssetsDTO assets(long userId) {
        SysUser user = requireUser(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        board.getLock().lock();
        try {
            double marketValue = marketValueOf(userId, board);
            double cash = user.getBalance().doubleValue();
            double debt = user.getDebt().doubleValue();

            AssetsDTO dto = new AssetsDTO();
            dto.setCash(cash);
            dto.setMarketValue(marketValue);
            dto.setDebt(debt);
            dto.setNetAsset(cash + marketValue - debt);
            dto.setGuardRatio(debt > 0 ? (cash + marketValue) / debt : 0.0);
            return dto;
        } finally {
            board.getLock().unlock();
        }
    }

    /** 持仓列表：档案 + 现价 mark-to-market + T+1 可卖股数。 */
    public List<PositionDTO> positions(long userId) {
        requireUser(userId);
        QuoteBoard board = quoteBoardRegistry.getOrLoad(userId);
        board.getLock().lock();
        try {
            List<UserPosition> holdings = userPositionMapper.selectList(
                    new LambdaQueryWrapper<UserPosition>()
                            .eq(UserPosition::getUserId, userId)
                            .orderByAsc(UserPosition::getStockId));
            List<PositionDTO> rows = new ArrayList<>(holdings.size());
            for (UserPosition holding : holdings) {
                Quote quote = board.quote(holding.getStockId());
                StockDefs.StockDef def = StockDefs.byId(holding.getStockId());
                rows.add(toPositionRow(holding, quote, def));
            }
            return rows;
        } finally {
            board.getLock().unlock();
        }
    }

    /** 交易流水：最近 TRADE_PAGE_SIZE 条，新在前。 */
    public PageResult<TradeItemDTO> trades(long userId) {
        requireUser(userId);
        List<TradeRecord> records = tradeRecordMapper.selectList(
                new LambdaQueryWrapper<TradeRecord>()
                        .eq(TradeRecord::getUserId, userId)
                        .orderByDesc(TradeRecord::getId)
                        .last("LIMIT " + TRADE_PAGE_SIZE));
        List<TradeItemDTO> rows = records.stream().map(this::toTradeRow).toList();
        return new PageResult<>(rows, rows.size());
    }

    /** 已解锁称号/徽章墙（未解锁不落库，故返回即已解锁）。 */
    public List<TitleDTO> titles(long userId) {
        requireUser(userId);
        List<UserTitle> rows = userTitleMapper.selectList(
                new LambdaQueryWrapper<UserTitle>()
                        .eq(UserTitle::getUserId, userId)
                        .orderByAsc(UserTitle::getGameDay)
                        .orderByAsc(UserTitle::getId));
        return rows.stream().map(row -> {
            TitleDTO dto = new TitleDTO();
            dto.setId(row.getTitleCode());
            dto.setName(row.getTitleName());
            dto.setDesc("BADGE".equals(row.getKind()) ? "阶段挑战徽章" : "成就称号");
            dto.setKind(row.getKind());
            dto.setGameDay(row.getGameDay() != null ? row.getGameDay() : 0);
            dto.setUnlocked(true);
            return dto;
        }).toList();
    }

    /** 市值 Σ(现价×股数)：行情板锁内调用。 */
    private double marketValueOf(long userId, QuoteBoard board) {
        List<UserPosition> holdings = userPositionMapper.selectList(
                new LambdaQueryWrapper<UserPosition>().eq(UserPosition::getUserId, userId));
        double total = 0.0;
        for (UserPosition holding : holdings) {
            total += board.quote(holding.getStockId()).getCurrent() * holding.getShares();
        }
        return total;
    }

    /** 持仓行组装（行情板锁内调用）。 */
    private PositionDTO toPositionRow(UserPosition holding, Quote quote, StockDefs.StockDef def) {
        PositionDTO row = new PositionDTO();
        row.setStockId(holding.getStockId());
        row.setCode(def.code());
        row.setName(def.name());
        row.setSector(def.sector());
        row.setShares(holding.getShares());
        row.setAvailableShares(holding.getAvailableShares());
        double costPrice = holding.getAvgCost().doubleValue();
        double current = quote.getCurrent();
        row.setCostPrice(costPrice);
        row.setCurrent(current);
        row.setMarketValue(current * holding.getShares());
        row.setProfit((current - costPrice) * holding.getShares());
        row.setProfitPct(costPrice > 0 ? (current - costPrice) / costPrice * 100 : 0.0);
        row.setHalted(quote.isHalted());
        row.setSt(quote.isSt());
        return row;
    }

    /** 流水行组装：side 小写、时间 yyyy-MM-dd HH:mm:ss。 */
    private TradeItemDTO toTradeRow(TradeRecord record) {
        TradeItemDTO row = new TradeItemDTO();
        row.setId(record.getId());
        row.setTime(record.getCreatedAt() != null ? TIME_FORMAT.format(record.getCreatedAt()) : null);
        row.setStockId(record.getStockId());
        StockDefs.StockDef def = StockDefs.byId(record.getStockId());
        row.setCode(def.code());
        row.setName(def.name());
        row.setSide(record.getTradeType().toLowerCase());
        row.setShares(record.getShares());
        row.setPrice(record.getPrice().doubleValue());
        row.setAmount(record.getAmount().doubleValue());
        row.setCommission(record.getCommission().doubleValue());
        row.setTax(record.getTax() != null ? record.getTax().doubleValue() : 0.0);
        return row;
    }

    /** 取账户行，无则 NOT_FOUND（同时校验世界已播种）。 */
    private SysUser requireUser(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return user;
    }
}
