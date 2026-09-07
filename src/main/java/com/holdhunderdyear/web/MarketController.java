package com.holdhunderdyear.web;

import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.common.R;
import com.holdhunderdyear.game.model.dto.DailySummaryDTO;
import com.holdhunderdyear.game.model.dto.EquityCurveDTO;
import com.holdhunderdyear.game.model.dto.IndexPointDTO;
import com.holdhunderdyear.game.model.dto.LeaderboardDTO;
import com.holdhunderdyear.game.model.dto.MarketStateDTO;
import com.holdhunderdyear.game.model.enums.MarketState;
import com.holdhunderdyear.market.FastForwardService;
import com.holdhunderdyear.market.MarketQueryService;
import com.holdhunderdyear.market.MarketStateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 市场接口（Task 10.3，spec §11）：状态聚合 / 开盘（幂等）/ 快进收官（终局态显式
 * 拒绝）/ 单日总结 / 日排行榜 / 资产曲线。查询聚合委托 MarketQueryService，
 * start/skip 编排在此层完成。业务拒绝经 BizException 统一转为 400 + R.fail(ErrorCode)。
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketStateService marketStateService;
    private final FastForwardService fastForwardService;
    private final MarketQueryService marketQueryService;

    public MarketController(MarketStateService marketStateService,
                            FastForwardService fastForwardService,
                            MarketQueryService marketQueryService) {
        this.marketStateService = marketStateService;
        this.fastForwardService = fastForwardService;
        this.marketQueryService = marketQueryService;
    }

    /** 市场状态聚合（只读）。 */
    @GetMapping("/state")
    public R<MarketStateDTO> state(@AuthenticationPrincipal Long userId) {
        return R.ok(marketQueryService.state(userId));
    }

    /** 开盘（幂等）：NOT_STARTED 开第 1 日、CLOSED 开下一日，盘中幂等返回。 */
    @PostMapping("/start")
    public R<MarketStateDTO> start(@AuthenticationPrincipal Long userId) {
        marketStateService.startDay(userId);
        return R.ok(marketQueryService.state(userId));
    }

    /** 快进到当日收官：终局态（BANKRUPT/FINISHED）显式拒绝，返回刚收官日的总结。 */
    @PostMapping("/skip")
    public R<DailySummaryDTO> skip(@AuthenticationPrincipal Long userId) {
        MarketState state = MarketState.valueOf(
                marketQueryService.requireGameState(userId).getMarketState());
        if (state == MarketState.BANKRUPT || state == MarketState.FINISHED) {
            throw new BizException(ErrorCode.FINISHED_STATE);
        }
        fastForwardService.skip(userId);
        return R.ok(marketQueryService.dailySummary(userId, null));
    }

    /** 单日收官总结：gameDay 缺省取最近结算日。 */
    @GetMapping("/daily-summary")
    public R<DailySummaryDTO> dailySummary(@AuthenticationPrincipal Long userId,
                                           @RequestParam(required = false) Integer gameDay) {
        return R.ok(marketQueryService.dailySummary(userId, gameDay));
    }

    /** 营业部日排行榜：gameDay 缺省取最近结算日。 */
    @GetMapping("/leaderboard")
    public R<LeaderboardDTO> leaderboard(@AuthenticationPrincipal Long userId,
                                         @RequestParam(required = false) Integer gameDay) {
        return R.ok(marketQueryService.leaderboard(userId, gameDay));
    }

    /** 资产 vs 指数曲线：game_day 0..最新结算日。 */
    @GetMapping("/equity-curve")
    public R<EquityCurveDTO> equityCurve(@AuthenticationPrincipal Long userId) {
        return R.ok(marketQueryService.equityCurve(userId));
    }

    /** 百年指数当日分时（spec §11 GET /api/market/index）。 */
    @GetMapping("/index")
    public R<List<IndexPointDTO>> index(@AuthenticationPrincipal Long userId) {
        return R.ok(marketQueryService.indexIntraday(userId));
    }
}
