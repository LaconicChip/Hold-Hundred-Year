package com.holdhundredyear.event;

import com.holdhundredyear.game.model.enums.EraCode;

import java.util.List;
import java.util.Set;

/**
 * 事件骨架（Phase 8.1）：一条可被掷出的事件的静态定义——编号、作用域、来源、
 * 可用纪元、权重、目标选择器、冲击模式、幅度区间、漂移跳数区间、真伪概率、
 * 文案变体与后续（followup）文案池。
 *
 * <p>约定：eras 为空 Set 表示全纪元可用；impactRange 带符号且不跨零（同号或一端为 0），
 * 掷 mag 时在两端间线性插值并保持符号；tickRange 仅供 SLOW_DRIFT/BUY_RUMOR 取
 * 漂移跳数（其余模式的跳数由模式内固定值决定）；truthProb 仅对 MEDIA/RUMOR/INSIDER
 * 生效（AUTHORITY 恒为真）。变体 sector 仅 SECTOR 骨架使用（null=全板块），
 * 槽位白名单为 {stock}/{amount}/{user}，且 {stock} 仅 STOCK 骨架可用。
 */
public record EventSkeleton(String code, Scope scope, Source source, Set<EraCode> eras,
                            double weight, TargetSelector target, ImpactPattern pattern,
                            double[] impactRange, int[] tickRange, double truthProb,
                            List<Variant> variants, List<String> followupTexts) {

    /** 文案变体：sector 仅 SECTOR 骨架使用（null=全板块），title/content 落 user_message 与 game_event。 */
    public record Variant(String sector, String title, String text) {
    }

    public enum Scope { STOCK, SECTOR, MARKET }

    public enum Source { AUTHORITY, MEDIA, RUMOR, INSIDER, FUN, NPC, SYSTEM }

    public enum TargetSelector { HOLDING_BIASED, ANY_STOCK }

    public enum ImpactPattern { INSTANT, SPIKE_FADE, BUY_RUMOR, SLOW_DRIFT, OVERSHOOT_REVERT, NEWS_FLIP, CHAIN }

    /** 紧凑构造器校验：所有断言消息用英文。 */
    public EventSkeleton {
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be > 0, got " + weight);
        }
        if (eras == null) {
            throw new IllegalArgumentException("eras must not be null (use empty set for all eras)");
        }
        if (impactRange == null || impactRange.length != 2 || impactRange[0] > impactRange[1]) {
            throw new IllegalArgumentException("impactRange must be [min, max], got "
                    + (impactRange == null ? "null" : impactRange[0] + ".." + impactRange[1]));
        }
        if (impactRange[0] < 0 && impactRange[1] > 0 || impactRange[0] > 0 && impactRange[1] < 0) {
            throw new IllegalArgumentException("impactRange must not cross zero: "
                    + impactRange[0] + ".." + impactRange[1]);
        }
        if (tickRange == null || tickRange.length != 2 || tickRange[0] > tickRange[1]) {
            throw new IllegalArgumentException("tickRange must be [min, max], got "
                    + (tickRange == null ? "null" : tickRange[0] + ".." + tickRange[1]));
        }
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("variants must not be empty");
        }
        if (truthProb < 0 || truthProb > 1) {
            throw new IllegalArgumentException("truthProb must be in [0,1], got " + truthProb);
        }
        if (source == Source.AUTHORITY && !followupTexts.isEmpty()) {
            throw new IllegalArgumentException("AUTHORITY skeleton must have no followupTexts");
        }
        if ((source == Source.MEDIA || source == Source.RUMOR || source == Source.INSIDER)
                && followupTexts.isEmpty()) {
            throw new IllegalArgumentException(source + " skeleton needs at least one followupText");
        }
    }
}
