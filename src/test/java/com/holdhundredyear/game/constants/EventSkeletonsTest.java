package com.holdhundredyear.game.constants;

import com.holdhundredyear.event.EventSkeleton;
import com.holdhundredyear.game.content.EventCopy;
import com.holdhundredyear.game.model.enums.EraCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件骨架池静态校验（纯 JUnit，无 Spring）：数量与唯一性、变体完备性、板块匹配、
 * 槽位白名单、followup 与来源匹配、真伪概率带、tickRange 合法性、CHAIN 全负、
 * 贸易战骨架纪元标签、纪元集合合法，以及骨架编号与 event-copy.txt 分组一一对应。
 */
class EventSkeletonsTest {

    private static final Pattern SLOT = Pattern.compile("\\{([a-z]+)\\}");

    private static final Set<String> SLOT_WHITELIST = Set.of("stock", "amount", "user");

    @Test
    void poolHoldsEverySkeletonAndStaysAboveTheSpecFloor() {
        assertTrue(EventSkeletons.ALL.size() >= 70, "pool must hold at least 70 skeletons");
        assertEquals(116, EventSkeletons.ALL.size(), "pool must hold exactly 116 skeletons");
    }

    @Test
    void copySectionsMatchSkeletonCodesExactly() {
        Set<String> codes = EventSkeletons.ALL.stream()
                .map(EventSkeleton::code)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> copyCodes = EventCopy.codes().stream()
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(codes, copyCodes,
                "resources/content/event-copy.txt must hold exactly one @section per skeleton code");
    }

    @Test
    void codesAreUnique() {
        Set<String> codes = new HashSet<>();
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            assertTrue(codes.add(skeleton.code()), "duplicate code: " + skeleton.code());
        }
    }

    @Test
    void everySkeletonHasAtLeastTwoWellFormedVariants() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            assertTrue(skeleton.variants().size() >= 2, "variants < 2: " + skeleton.code());
            for (EventSkeleton.Variant variant : skeleton.variants()) {
                assertFalse(variant.title().isBlank(), "blank title: " + skeleton.code());
                assertFalse(variant.text().isBlank(), "blank text: " + skeleton.code());
            }
        }
    }

    @Test
    void sectorVariantsMatchSkeletonScope() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            for (EventSkeleton.Variant variant : skeleton.variants()) {
                if (skeleton.scope() == EventSkeleton.Scope.SECTOR) {
                    assertTrue(variant.sector() == null || StockDefs.ALL_SECTORS.contains(variant.sector()),
                            "illegal sector variant: " + skeleton.code() + "/" + variant.sector());
                } else {
                    assertEquals(null, variant.sector(),
                            "non-SECTOR skeleton must not carry sector: " + skeleton.code());
                }
            }
        }
    }

    @Test
    void slotsStayWithinWhitelist() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            for (String text : allTexts(skeleton)) {
                Matcher matcher = SLOT.matcher(text);
                while (matcher.find()) {
                    assertTrue(SLOT_WHITELIST.contains(matcher.group(1)),
                            "illegal slot {" + matcher.group(1) + "} in " + skeleton.code());
                }
            }
        }
    }

    @Test
    void nonStockScopeNeverUsesStockSlot() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            if (skeleton.scope() == EventSkeleton.Scope.STOCK) {
                continue;
            }
            for (String text : allTexts(skeleton)) {
                assertFalse(text.contains("{stock}"),
                        "{stock} slot in non-STOCK skeleton: " + skeleton.code());
            }
        }
    }

    @Test
    void followupPresenceMatchesSource() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            if (skeleton.source() == EventSkeleton.Source.AUTHORITY) {
                assertTrue(skeleton.followupTexts().isEmpty(),
                        "AUTHORITY skeleton must have no followups: " + skeleton.code());
            } else if (skeleton.source() == EventSkeleton.Source.MEDIA
                    || skeleton.source() == EventSkeleton.Source.RUMOR
                    || skeleton.source() == EventSkeleton.Source.INSIDER) {
                assertTrue(skeleton.followupTexts().size() >= 1,
                        "source " + skeleton.source() + " needs followups: " + skeleton.code());
            }
        }
    }

    @Test
    void truthProbStaysInSourceBand() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            double p = skeleton.truthProb();
            switch (skeleton.source()) {
                case AUTHORITY -> assertEquals(1.0, p, 0.0, "truthProb band: " + skeleton.code());
                case MEDIA -> assertTrue(p >= 0.75 && p <= 0.85, "truthProb band: " + skeleton.code());
                case RUMOR -> assertTrue(p >= 0.40 && p <= 0.50, "truthProb band: " + skeleton.code());
                case INSIDER -> assertTrue(p >= 0.65 && p <= 0.75, "truthProb band: " + skeleton.code());
                default -> { }
            }
        }
    }

    @Test
    void tickRangeIsValid() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            assertEquals(2, skeleton.tickRange().length, "tickRange length: " + skeleton.code());
            assertTrue(skeleton.tickRange()[0] >= 1, "tickRange[0] >= 1: " + skeleton.code());
            assertTrue(skeleton.tickRange()[0] <= skeleton.tickRange()[1],
                    "tickRange min <= max: " + skeleton.code());
        }
    }

    @Test
    void chainSkeletonsImpactOnlyDownward() {
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            if (skeleton.pattern() == EventSkeleton.ImpactPattern.CHAIN) {
                assertTrue(skeleton.impactRange()[0] < 0 && skeleton.impactRange()[1] < 0,
                        "CHAIN skeleton must be all-negative: " + skeleton.code());
            }
        }
    }

    @Test
    void tradeWarSkeletonsCarryExactTradeWarEra() {
        for (String code : List.of("policy-trade-tariff", "policy-crackdown-chip")) {
            EventSkeleton skeleton = byCode(code);
            assertEquals(Set.of(EraCode.TRADE_WAR), skeleton.eras(), "eras of " + code);
        }
    }

    @Test
    void erasStayWithinEraCodeUniverse() {
        EnumSet<EraCode> universe = EnumSet.allOf(EraCode.class);
        for (EventSkeleton skeleton : EventSkeletons.ALL) {
            assertTrue(universe.containsAll(skeleton.eras()), "illegal eras: " + skeleton.code());
        }
    }

    /** 骨架全部文案（title + text + followupTexts），槽位类断言共用。 */
    private static List<String> allTexts(EventSkeleton skeleton) {
        List<String> texts = new ArrayList<>();
        for (EventSkeleton.Variant variant : skeleton.variants()) {
            texts.add(variant.title());
            texts.add(variant.text());
        }
        texts.addAll(skeleton.followupTexts());
        return texts;
    }

    private static EventSkeleton byCode(String code) {
        return EventSkeletons.ALL.stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }
}
