package com.holdhundredyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.PageResult;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.model.dto.AssetsDTO;
import com.holdhundredyear.game.model.dto.IpoRecordItemDTO;
import com.holdhundredyear.game.model.dto.LoanRecordItemDTO;
import com.holdhundredyear.game.model.dto.PositionDTO;
import com.holdhundredyear.game.model.dto.TitleDTO;
import com.holdhundredyear.game.model.dto.StockTradeMarkDTO;
import com.holdhundredyear.game.model.dto.TradeItemDTO;
import com.holdhundredyear.market.QuoteBoardRegistry;
import com.holdhundredyear.market.entity.LoanRecord;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.TradeRecord;
import com.holdhundredyear.market.entity.UserIpoRecord;
import com.holdhundredyear.market.entity.UserPosition;
import com.holdhundredyear.market.entity.UserTitle;
import com.holdhundredyear.market.mapper.LoanRecordMapper;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.TradeRecordMapper;
import com.holdhundredyear.market.mapper.UserIpoRecordMapper;
import com.holdhundredyear.market.mapper.UserPositionMapper;
import com.holdhundredyear.market.mapper.UserTitleMapper;
import com.holdhundredyear.market.quote.Quote;
import com.holdhundredyear.market.quote.QuoteBoard;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人资产/持仓/三类流水/称号查询聚合（spec §11 GET /api/me/{assets,positions,
 * trades,loan-records,ipo-records,titles}，对应 spec §12.4 资产页的交易/融资/打新流水）。
 * 现价一律读本账户行情板（世界锁内快照读取，mark-to-market 与交易同源）；无 game_state
 * 抛 NOT_FOUND。
 */
@Service
public class MeQueryService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 流水类查询的单页条数：交易/融资/打新共用（最近 N 条，新在前）。 */
    private static final int FLOW_PAGE_SIZE = 100;

    private final SysUserMapper sysUserMapper;
    private final UserPositionMapper userPositionMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final LoanRecordMapper loanRecordMapper;
    private final UserIpoRecordMapper userIpoRecordMapper;
    private final UserTitleMapper userTitleMapper;
    private final QuoteBoardRegistry quoteBoardRegistry;

    public MeQueryService(SysUserMapper sysUserMapper,
                          UserPositionMapper userPositionMapper,
                          TradeRecordMapper tradeRecordMapper,
                          LoanRecordMapper loanRecordMapper,
                          UserIpoRecordMapper userIpoRecordMapper,
                          UserTitleMapper userTitleMapper,
                          QuoteBoardRegistry quoteBoardRegistry) {
        this.sysUserMapper = sysUserMapper;
        this.userPositionMapper = userPositionMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.loanRecordMapper = loanRecordMapper;
        this.userIpoRecordMapper = userIpoRecordMapper;
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
            BigDecimal sharkDebtAmount = SharkService.sharkOf(user);
            double sharkDebt = sharkDebtAmount.doubleValue();
            double marginRoom = MarginService.marginBorrowableOf(
                    BigDecimal.valueOf(cash + marketValue), BigDecimal.valueOf(debt), sharkDebtAmount).doubleValue();

            AssetsDTO dto = new AssetsDTO();
            dto.setCash(cash);
            dto.setMarketValue(marketValue);
            // 负债给合计（融资 + 高利贷）：只欠高利贷时显示 0.00 是在骗人；融资债单列在 sharkDebt 里可减出
            dto.setDebt(debt + sharkDebt);
            dto.setSharkDebt(sharkDebt);
            dto.setNetAsset(cash + marketValue - debt - sharkDebt);
            dto.setGuardRatio(debt > 0 ? (cash + marketValue) / debt : 0.0);
            dto.setBorrowable(Math.max(marginRoom, 0.0));
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

    /** 交易流水：最近 FLOW_PAGE_SIZE 条，新在前。 */
    public PageResult<TradeItemDTO> trades(long userId) {
        requireUser(userId);
        List<TradeRecord> records = tradeRecordMapper.selectList(
                new LambdaQueryWrapper<TradeRecord>()
                        .eq(TradeRecord::getUserId, userId)
                        .orderByDesc(TradeRecord::getId)
                        .last("LIMIT " + FLOW_PAGE_SIZE));
        List<TradeItemDTO> rows = records.stream().map(this::toTradeRow).toList();
        return new PageResult<>(rows, rows.size());
    }

    /** 某只票的全部成交标记（按 gameDay,tickIndex 升序），供日K/分时图渲染 B/S。 */
    public List<StockTradeMarkDTO> stockTrades(long userId, long stockId) {
        requireUser(userId);
        return tradeRecordMapper.selectList(new LambdaQueryWrapper<TradeRecord>()
                        .eq(TradeRecord::getUserId, userId)
                        .eq(TradeRecord::getStockId, stockId)
                        .orderByAsc(TradeRecord::getGameDay)
                        .orderByAsc(TradeRecord::getTickIndex)
                        .orderByAsc(TradeRecord::getId))
                .stream().map(this::toTradeMark).toList();
    }

    /** 融资流水：最近 FLOW_PAGE_SIZE 条，新在前（借款/还款/结息/强平还款同表）。 */
    public PageResult<LoanRecordItemDTO> loanRecords(long userId) {
        requireUser(userId);
        List<LoanRecord> records = loanRecordMapper.selectList(
                new LambdaQueryWrapper<LoanRecord>()
                        .eq(LoanRecord::getUserId, userId)
                        .orderByDesc(LoanRecord::getId)
                        .last("LIMIT " + FLOW_PAGE_SIZE));
        List<LoanRecordItemDTO> rows = records.stream().map(this::toLoanRow).toList();
        return new PageResult<>(rows, rows.size());
    }

    /** 打新记录：最近 FLOW_PAGE_SIZE 条，新在前（含未中签/放弃缴款等终态）。 */
    public PageResult<IpoRecordItemDTO> ipoRecords(long userId) {
        requireUser(userId);
        List<UserIpoRecord> records = userIpoRecordMapper.selectList(
                new LambdaQueryWrapper<UserIpoRecord>()
                        .eq(UserIpoRecord::getUserId, userId)
                        .orderByDesc(UserIpoRecord::getId)
                        .last("LIMIT " + FLOW_PAGE_SIZE));
        List<IpoRecordItemDTO> rows = records.stream().map(this::toIpoRow).toList();
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
        row.setGameDay(record.getGameDay() != null ? record.getGameDay() : 0);
        row.setTickIndex(record.getTickIndex() != null ? record.getTickIndex() : 0);
        return row;
    }

    /** B/S 标记行：只保留图上需要的定位与成交信息。 */
    private StockTradeMarkDTO toTradeMark(TradeRecord record) {
        StockTradeMarkDTO row = new StockTradeMarkDTO();
        row.setId(record.getId());
        row.setGameDay(record.getGameDay() != null ? record.getGameDay() : 0);
        row.setTickIndex(record.getTickIndex() != null ? record.getTickIndex() : 0);
        row.setSide(record.getTradeType());
        row.setPrice(record.getPrice().doubleValue());
        row.setShares(record.getShares());
        return row;
    }

    /** 融资流水行组装：类型原样透出，金额为元，time 允许为空（DB 默认值补齐前）。 */
    private LoanRecordItemDTO toLoanRow(LoanRecord record) {
        LoanRecordItemDTO row = new LoanRecordItemDTO();
        row.setId(record.getId());
        row.setTime(record.getCreatedAt() != null ? TIME_FORMAT.format(record.getCreatedAt()) : null);
        row.setLoanType(record.getLoanType());
        row.setAmount(record.getAmount().doubleValue());
        row.setDebtAfter(record.getDebtAfter().doubleValue());
        row.setGameDay(record.getGameDay() != null ? record.getGameDay() : 0);
        return row;
    }

    /** 打新记录行组装：profit 为空（未结算）记 0，前端按 status 判定是否展示盈亏。 */
    private IpoRecordItemDTO toIpoRow(UserIpoRecord record) {
        IpoRecordItemDTO row = new IpoRecordItemDTO();
        row.setId(record.getId());
        row.setTime(record.getCreatedAt() != null ? TIME_FORMAT.format(record.getCreatedAt()) : null);
        row.setIpoName(record.getIpoName());
        row.setIssuePrice(record.getIssuePrice().doubleValue());
        row.setStatus(record.getStatus());
        row.setProfit(record.getProfit() != null ? record.getProfit().doubleValue() : 0.0);
        row.setGameDay(record.getGameDay() != null ? record.getGameDay() : 0);
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
