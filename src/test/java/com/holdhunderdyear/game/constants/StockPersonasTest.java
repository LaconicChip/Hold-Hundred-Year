package com.holdhunderdyear.game.constants;

import com.holdhunderdyear.game.constants.StockDefs.StockDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个股人设档案的完整性与"反重复"校验（纯单测，不连库）：
 * 覆盖 24 只票、每条文案非空白、无 emoji、事件文案都带 {stock} 槽位，
 * 以及个股专属池 + 板块池 + 全局池合起来不出现重复句子——
 * 文案量堆上去却互相抄，等于没堆。
 */
class StockPersonasTest {

    /** emoji 常见区段（含表情符号、符号象形、交通地图、杂项符号与变体选择符）。 */
    private static final Pattern EMOJI =
            Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    @Test
    void everyStockHasAPersonaAndIdsMatchStockDefsExactly() {
        assertEquals(24, StockPersonas.ALL.size());
        for (StockDef def : StockDefs.ALL) {
            assertTrue(StockPersonas.has(def.id()), "no persona for " + def.code() + " " + def.name());
            assertSame(StockPersonas.byId(def.id()), StockPersonas.byId(def.id()), "byId must be stable");
        }
    }

    @Test
    void personaPoolsAreRichEnoughToAvoidRepeatingThemselves() {
        for (StockPersonas.Persona persona : StockPersonas.ALL) {
            String who = "stock " + persona.stockId();
            assertFalse(persona.nickname().isBlank(), who + " needs a nickname");
            assertFalse(persona.temper().isBlank(), who + " needs a temper line");
            assertTrue(persona.danmu().size() >= 5, who + " must own at least 5 danmu lines");
            assertTrue(persona.news().size() >= 3, who + " must own at least 3 event lines");
            for (String line : persona.danmu()) {
                assertReadable(line, who + " danmu");
            }
            for (String line : persona.news()) {
                assertReadable(line, who + " news");
                assertTrue(line.contains("{stock}"),
                        who + " event line must name the stock through the {stock} slot: " + line);
            }
        }
    }

    @Test
    void sectorFlavorCoversEverySectorInStockDefs() {
        for (StockDef def : StockDefs.ALL) {
            List<String> pool = DanmuTexts.sectorPool(def.sector());
            assertTrue(pool.size() >= 4, "sector " + def.sector() + " needs at least 4 lines");
            assertSame(pool, DanmuTexts.sectorPool(def.sector()), "sector pool lookup must be stable");
        }
    }

    @Test
    void noLineIsDuplicatedAcrossAllThreeTiers() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        List<List<String>> allPools = new ArrayList<>();
        allPools.add(DanmuTexts.INDEX_DROP);
        allPools.add(DanmuTexts.INDEX_RISE);
        allPools.add(DanmuTexts.LIMIT_UP);
        allPools.add(DanmuTexts.LIMIT_DOWN);
        allPools.add(DanmuTexts.RALLY);
        allPools.add(DanmuTexts.DIVE);
        allPools.add(DanmuTexts.RALLY_MINOR);
        allPools.add(DanmuTexts.DIVE_MINOR);
        allPools.add(DanmuTexts.FLAT);
        DanmuTexts.SECTOR_FLAVOR.values().forEach(allPools::add);
        StockPersonas.ALL.forEach((persona) -> allPools.add(persona.danmu()));
        for (List<String> pool : allPools) {
            for (String line : pool) {
                if (!seen.add(line)) {
                    duplicates.add(line);
                }
            }
        }
        assertTrue(duplicates.isEmpty(), "repeated copy across pools kills variety: " + duplicates);
        assertTrue(seen.size() >= 300,
                "total distinct chatter lines must stay big enough to feel alive, got " + seen.size());
    }

    private static void assertReadable(String line, String what) {
        assertFalse(line == null || line.isBlank(), what + " line must not be blank");
        assertFalse(EMOJI.matcher(line).find(), what + " must not contain emoji: " + line);
        assertFalse(line.contains("占位") || line.contains("TODO") || line.contains("XXX"),
                what + " must not ship placeholder copy: " + line);
    }
}
