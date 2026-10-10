package com.holdhundredyear.web;

import com.holdhundredyear.common.PageResult;
import com.holdhundredyear.common.R;
import com.holdhundredyear.game.model.dto.AssetsDTO;
import com.holdhundredyear.game.model.dto.IpoRecordItemDTO;
import com.holdhundredyear.game.model.dto.LoanRecordItemDTO;
import com.holdhundredyear.game.model.dto.PositionDTO;
import com.holdhundredyear.game.model.dto.TitleDTO;
import com.holdhundredyear.game.model.dto.TradeItemDTO;
import com.holdhundredyear.trade.MeQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 个人资料接口（spec §11）：资产看板 / 持仓列表 / 交易·融资·打新流水分页 / 已解锁称号徽章。
 * 只读查询委托 MeQueryService（现价 mark-to-market 与交易同源）。
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeQueryService meQueryService;

    public MeController(MeQueryService meQueryService) {
        this.meQueryService = meQueryService;
    }

    /** 资产四项 + 担保比例（spec §6.2）。 */
    @GetMapping("/assets")
    public R<AssetsDTO> assets(@AuthenticationPrincipal Long userId) {
        return R.ok(meQueryService.assets(userId));
    }

    /** 持仓列表（含可卖股数、成本、浮盈、停牌/ST 标识）。 */
    @GetMapping("/positions")
    public R<List<PositionDTO>> positions(@AuthenticationPrincipal Long userId) {
        return R.ok(meQueryService.positions(userId));
    }

    /** 交易流水分页（最近 100 条，新在前）。 */
    @GetMapping("/trades")
    public R<PageResult<TradeItemDTO>> trades(@AuthenticationPrincipal Long userId) {
        return R.ok(meQueryService.trades(userId));
    }

    /** 融资流水分页（借款/还款/结息/强平还款，最近 100 条，新在前）。 */
    @GetMapping("/loan-records")
    public R<PageResult<LoanRecordItemDTO>> loanRecords(@AuthenticationPrincipal Long userId) {
        return R.ok(meQueryService.loanRecords(userId));
    }

    /** 打新记录分页（含未中签等终态，最近 100 条，新在前）。 */
    @GetMapping("/ipo-records")
    public R<PageResult<IpoRecordItemDTO>> ipoRecords(@AuthenticationPrincipal Long userId) {
        return R.ok(meQueryService.ipoRecords(userId));
    }

    /** 已解锁称号与挑战徽章（kind 区分）。 */
    @GetMapping("/titles")
    public R<List<TitleDTO>> titles(@AuthenticationPrincipal Long userId) {
        return R.ok(meQueryService.titles(userId));
    }
}
