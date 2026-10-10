package com.holdhundredyear.game.content;

import com.holdhundredyear.game.constants.StockDefs.StockDef;
import com.holdhundredyear.game.constants.StockDefs;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 板块文案库校验（纯单测）：StockDefs 里出现的每个板块都必须有齐四类文案，
 * 每条标题正文非空白、无 emoji、传闻类必须带配套追报，且全库不出现重复句子。
 */
class SectorFlavorTest {

    private static final Pattern EMOJI =
            Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    @Test
    void everySectorCoversAllFourChannels() {
        for (StockDef def : StockDefs.ALL) {
            for (SectorFlavor.Channel channel : SectorFlavor.Channel.values()) {
                List<SectorFlavor.Entry> pool = SectorFlavor.pool(def.sector(), channel);
                assertTrue(pool.size() >= 2,
                        "sector " + def.sector() + " channel " + channel + " needs at least 2 entries");
                assertEquals(pool, SectorFlavor.pool(def.sector(), channel), "pool lookup must be stable");
            }
        }
        assertFalse(SectorFlavor.pool("不存在的板块", SectorFlavor.Channel.AUTHORITY).isEmpty(),
                "unknown sector must fall back to the market-wide pool, never empty");
    }

    @Test
    void entriesAreReadableAndRumorsCarryTheirFollowup() {
        for (String sector : SectorFlavor.sectors()) {
            for (SectorFlavor.Channel channel : SectorFlavor.Channel.values()) {
                for (SectorFlavor.Entry entry : SectorFlavor.pool(sector, channel)) {
                    String what = sector + "/" + channel + " " + entry.title();
                    assertFalse(entry.title().isBlank(), what + " has blank title");
                    assertFalse(entry.text().isBlank(), what + " has blank text");
                    assertFalse(EMOJI.matcher(entry.title() + entry.text()).find(), what + " contains emoji");
                    assertFalse(entry.text().contains("占位"), what + " ships placeholder copy");
                    if (channel == SectorFlavor.Channel.RUMOR) {
                        assertFalse(entry.reveal() == null || entry.reveal().isBlank(),
                                what + " (rumor) must carry its own confirm/deny follow-up");
                    }
                }
            }
        }
    }

    @Test
    void noSectorEntryRepeatsAnother() {
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (String sector : SectorFlavor.sectors()) {
            for (SectorFlavor.Channel channel : SectorFlavor.Channel.values()) {
                for (SectorFlavor.Entry entry : SectorFlavor.pool(sector, channel)) {
                    for (String piece : new String[]{entry.title(), entry.text(),
                            entry.reveal() == null ? "" : entry.reveal()}) {
                        if (piece.isEmpty()) {
                            continue;
                        }
                        total++;
                        assertTrue(seen.add(piece), "duplicated sector copy: " + piece);
                    }
                }
            }
        }
        assertTrue(total >= 390, "sector copy volume dropped to " + total);
    }
}
