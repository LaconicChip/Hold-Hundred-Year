package com.holdhundredyear.game.constants;

import com.holdhundredyear.event.EventSkeleton;
import com.holdhundredyear.event.EventSkeleton.ImpactPattern;
import com.holdhundredyear.event.EventSkeleton.Scope;
import com.holdhundredyear.event.EventSkeleton.Source;
import com.holdhundredyear.event.EventSkeleton.TargetSelector;
import com.holdhundredyear.event.EventSkeleton.Variant;
import com.holdhundredyear.game.content.EventCopy;
import com.holdhundredyear.game.model.enums.EraCode;

import java.util.List;
import java.util.Set;

/**
 * 事件骨架池（Phase 8.1，spec §8.1）：92 条骨架分三池——
 * 池① fund- 前缀 35 条个股公告/报道（STOCK），池② policy- 前缀 37 条宏观与板块政策
 * （MARKET 7 + SECTOR 30，其中 17 条是 2026-10 补的真实历史名场面），池③ rumor- 前缀 20 条
 * 持股偏置的传闻/内幕（RUMOR 15 + INSIDER 5）。池①②的 STOCK 骨架用 ANY_STOCK；池③全部 HOLDING_BIASED；
 * SECTOR/MARKET 骨架 target 填 ANY_STOCK（流程不使用）。
 *
 * <p>本文件只写"结构"：作用域、来源、纪元、权重、目标选择器、冲击模式、幅度区间。
 * 标题与正文（以及证实/辟谣的追报文案）全部在 <code>resources/content/event-copy.txt</code>
 * 里按骨架编号取，改措辞不再动 Java，见 {@link EventCopy}。
 *
 * <p>真伪概率按来源带状取基准：AUTHORITY=1.0、MEDIA=0.80、RUMOR=0.45、INSIDER=0.70；
 * tickRange 由模式推导：SLOW_DRIFT [10,31]、BUY_RUMOR [10,21]、其余 [1,1]——
 * 均由 {@code sk} 工厂统一下发，避免逐条手写走样。
 */
public final class EventSkeletons {

    public static final List<EventSkeleton> ALL = List.of(
            // ================= 池① fund- ：35 条个股事件 =================
            // ---- AUTHORITY×20（truthProb=1.0，无 followup）----
            sk("fund-earnings-preinc-good", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.08),
            sk("fund-earnings-preinc-bad", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.08, -0.03),
            sk("fund-turnaround", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("fund-facechange", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.08, -0.03),
            sk("fund-holder-reduce", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.08, -0.03),
            sk("fund-holder-buy", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("fund-high-transfer", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.03, 0.08),
            sk("fund-lockup-release", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.08, -0.03),
            sk("fund-private-placement", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.08),
            sk("fund-esop", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("fund-investigate", Scope.STOCK, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.CHAIN, -0.09, -0.05),
            sk("fund-goodwill-impairment", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.08, -0.03),
            sk("fund-audit-bad", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.08, -0.03),
            sk("fund-big-order", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.08),
            sk("fund-dividend", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.08),
            sk("fund-st-hat", Scope.STOCK, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.08, -0.03),
            sk("fund-st-unhat", Scope.STOCK, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("fund-contract-terminated", Scope.STOCK, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.CHAIN, -0.09, -0.05),
            sk("fund-lawsuit", Scope.STOCK, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.08, -0.03),
            sk("fund-pledge-crisis", Scope.STOCK, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.CHAIN, -0.09, -0.05),
            // ---- MEDIA×15（truthProb=0.80，每条 2~3 条中立 followup）----
            sk("fund-price-up", Scope.STOCK, Source.MEDIA,
                    3, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("fund-price-down", Scope.STOCK, Source.MEDIA,
                    3, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, -0.12, -0.05),
            sk("fund-chip-tapeout", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.03, 0.08),
            sk("fund-chip-fail", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.08, -0.03),
            sk("fund-drug-clinical", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.03, 0.08),
            sk("fund-entity-list", Scope.STOCK, Source.MEDIA,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.CHAIN, -0.09, -0.05),
            sk("fund-hotspot-reply", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.03, 0.08),
            sk("fund-rename-tech", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.03, 0.08),
            sk("fund-dragon-list", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.08),
            sk("fund-index-include", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.08),
            sk("fund-broker-cover", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("fund-cross-acquire", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.03, 0.08),
            sk("fund-safety-accident", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.08, -0.03),
            sk("fund-epa-penalty", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.08, -0.03),
            sk("fund-exec-resign", Scope.STOCK, Source.MEDIA,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.08, -0.03),
            // ================= 池② policy- ：20 条宏观与板块政策 =================
            // ---- MARKET×6（AUTHORITY，无 followup，target=ANY_STOCK 流程不使用）----
            sk("policy-rrr-cut", Scope.MARKET, Source.AUTHORITY,
                    3, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.02, 0.05),
            sk("policy-stamp-tax-cut", Scope.MARKET, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.02, 0.05),
            sk("policy-ipo-tighten", Scope.MARKET, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, 0.02, 0.05),
            sk("policy-national-team", Scope.MARKET, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.02, 0.06),
            sk("policy-fed-hike", Scope.MARKET, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.06, -0.02),
            sk("policy-us-crash", Scope.MARKET, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.05, -0.02),
            // ---- SECTOR×14（AUTHORITY，无 followup；板块变体 sector 精确匹配 StockDefs.ALL_SECTORS）----
            sk("policy-crackdown", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.07, -0.03),
            sk("policy-drug-tender", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.07, -0.03),
            sk("policy-carbon-subsidy", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.04, 0.10),
            sk("policy-property-ease", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.07),
            sk("policy-pig-up", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.04, 0.10),
            sk("policy-lithium-price-war", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.07, -0.03),
            sk("policy-liquor-price-up", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.04, 0.10),
            sk("policy-crackdown-chip", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.TRADE_WAR),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.07, -0.03),
            sk("policy-ai-windfall", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.AI_BULL),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.04, 0.10),
            sk("policy-new-energy-windfall", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.NEW_ENERGY),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.04, 0.10),
            sk("policy-lowalt-windfall", Scope.SECTOR, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.07),
            sk("policy-robot-windfall", Scope.SECTOR, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.07),
            sk("policy-sector-rectify", Scope.SECTOR, Source.AUTHORITY,
                    2, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.07, -0.03),
            sk("policy-trade-tariff", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.TRADE_WAR),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.CHAIN, -0.09, -0.04),
            // ---- 历史名场面补充×17（spec §4.4/§7.3：真实事件化用，按板块/大盘归档）----
            sk("policy-soe-valuation", Scope.SECTOR, Source.AUTHORITY,
                    Set.of(EraCode.AI_BULL, EraCode.REBALANCE),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.04, 0.09),
            sk("policy-double-reduction", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.NEW_ENERGY),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.12, -0.06),
            sk("policy-plasticizer-liquor", Scope.SECTOR, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.09, -0.04),
            sk("policy-scallop-migration", Scope.SECTOR, Source.AUTHORITY,
                    1, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.10, -0.05),
            sk("policy-chip-shortage", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.NEW_ENERGY),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("policy-polysilicon-squeeze", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.AI_BULL),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.06, 0.12),
            sk("policy-guzi-ip-boom", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.REBALANCE),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.05, 0.10),
            sk("policy-rare-earth-control", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.TRADE_WAR),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.04, 0.09),
            sk("policy-insurance-rate-cut", Scope.SECTOR, Source.AUTHORITY,
                    Set.of(EraCode.AI_BULL, EraCode.REBALANCE),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, -0.07, -0.03),
            sk("policy-appliance-trade-in", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.REBALANCE),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.07),
            sk("policy-express-price-war", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.NEW_ENERGY),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, -0.10, -0.04),
            sk("policy-air-route-halt", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.BLUE_CHIP),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.OVERSHOOT_REVERT, -0.11, -0.05),
            sk("policy-ship-order-full", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.REBALANCE),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.04, 0.10),
            sk("policy-compute-voucher", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.AI_BULL),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.SLOW_DRIFT, 0.05, 0.12),
            sk("policy-satellite-network", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.NEW_ENERGY),
                    1, TargetSelector.ANY_STOCK, ImpactPattern.SPIKE_FADE, 0.05, 0.10),
            sk("policy-broker-merger", Scope.SECTOR, Source.AUTHORITY, Set.of(EraCode.REBALANCE),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.05, 0.10),
            // 大盘：化用 2024 年 9 月末的政策组合拳（与 §4.4 千股涨停日同一条叙事线）
            sk("policy-bazooka-924", Scope.MARKET, Source.AUTHORITY,
                    Set.of(EraCode.AI_BULL, EraCode.REBALANCE),
                    2, TargetSelector.ANY_STOCK, ImpactPattern.INSTANT, 0.03, 0.06),
            // ================= 池③ rumor- ：20 条持股偏置传闻/内幕 =================
            // ---- RUMOR×15（truthProb=0.45，全部 HOLDING_BIASED）----
            sk("rumor-restructure", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.05, 0.12),
            sk("rumor-backdoor", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.05, 0.12),
            sk("rumor-chief-leak", Scope.STOCK, Source.RUMOR,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.NEWS_FLIP, 0.06, 0.10),
            sk("rumor-bigv-call", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.SPIKE_FADE, 0.04, 0.09),
            sk("rumor-teacher-hitting", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.SPIKE_FADE, 0.05, 0.10),
            sk("rumor-private-placement", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.05, 0.12),
            sk("rumor-halt-check", Scope.STOCK, Source.RUMOR,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.NEWS_FLIP, 0.06, 0.10),
            sk("rumor-buyback", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.04, 0.09),
            sk("rumor-big-contract", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.05, 0.12),
            sk("rumor-exec-resign", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.OVERSHOOT_REVERT, -0.10, -0.04),
            sk("rumor-cross-acquire", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.SPIKE_FADE, 0.04, 0.09),
            sk("rumor-tech-breakthrough", Scope.STOCK, Source.RUMOR,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.NEWS_FLIP, 0.06, 0.10),
            sk("rumor-policy-leak", Scope.SECTOR, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.05, 0.10),
            sk("rumor-main-force", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.04, 0.09),
            sk("rumor-triple-limit", Scope.STOCK, Source.RUMOR,
                    2, TargetSelector.HOLDING_BIASED, ImpactPattern.SPIKE_FADE, 0.05, 0.10),
            // ---- INSIDER×5（truthProb=0.70，全部 HOLDING_BIASED）----
            sk("rumor-insider-order", Scope.STOCK, Source.INSIDER,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.06, 0.12),
            sk("rumor-insider-restructure", Scope.STOCK, Source.INSIDER,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.06, 0.12),
            sk("rumor-insider-earnings", Scope.STOCK, Source.INSIDER,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.NEWS_FLIP, 0.06, 0.10),
            sk("rumor-insider-investigation", Scope.STOCK, Source.INSIDER,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.NEWS_FLIP, 0.08, 0.12),
            sk("rumor-insider-halt", Scope.STOCK, Source.INSIDER,
                    1, TargetSelector.HOLDING_BIASED, ImpactPattern.BUY_RUMOR, 0.06, 0.12)
    );

    private EventSkeletons() {
    }

    /** 骨架工厂：文案按编号从 event-copy.txt 取；truthProb 与 tickRange 由来源/模式推导。 */
    private static EventSkeleton sk(String code, Scope scope, Source source, double weight,
                                    TargetSelector target, ImpactPattern pattern,
                                    double lo, double hi) {
        return sk(code, scope, source, Set.of(), weight, target, pattern, lo, hi);
    }

    /** 骨架工厂（带纪元）：eras 非空限定可用纪元。 */
    private static EventSkeleton sk(String code, Scope scope, Source source, Set<EraCode> eras, double weight,
                                    TargetSelector target, ImpactPattern pattern,
                                    double lo, double hi) {
        EventCopy.Pools copy = EventCopy.of(code);
        double truthProb = switch (source) {
            case AUTHORITY -> 1.0;
            case MEDIA -> 0.80;
            case RUMOR -> 0.45;
            case INSIDER -> 0.70;
            default -> throw new IllegalArgumentException("unsupported source in skeletons: " + source);
        };
        int[] ticks = switch (pattern) {
            case SLOW_DRIFT -> new int[]{10, 31};
            case BUY_RUMOR -> new int[]{10, 21};
            default -> new int[]{1, 1};
        };
        List<Variant> variants = copy.items().stream()
                .map((item) -> new Variant(item.sector(), item.title(), item.text()))
                .toList();
        return new EventSkeleton(code, scope, source, eras, weight, target, pattern,
                new double[]{lo, hi}, ticks, truthProb, variants, copy.followups());
    }
}
