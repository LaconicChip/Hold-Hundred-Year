package com.holdhunderdyear.game.content;

import com.holdhunderdyear.game.model.enums.EraCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纪元叙事文案校验（纯单测，不连库）：EraCode 的 11 个纪元都要有且只有一句叙事，
 * 非空白、无 emoji、无占位，且是一句像样的话。纪元切换通知是玩家感知"换了个年代"
 * 的唯一信号，11 句互相抄或退化成标题复读，等于没换纪元。
 * 文案本体在 <code>resources/content/era-narratives.txt</code>。
 */
class EraNarrativesTest {

    /** emoji 常见区段（与弹幕/人设文案测试同一把尺子）。 */
    private static final Pattern EMOJI =
            Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    @Test
    void everyEraHasOneSubstantialNarrative() {
        for (EraCode code : EraCode.values()) {
            String text = EraNarratives.narrative(code);
            assertFalse(text == null || text.isBlank(), "era " + code + " must carry a narrative");
            assertFalse(EMOJI.matcher(text).find(), "era " + code + " narrative contains emoji: " + text);
            for (String token : List.of("TODO", "XXX", "占位")) {
                assertFalse(text.contains(token), "placeholder copy in era " + code + ": " + text);
            }
            assertTrue(text.length() >= 12,
                    "era " + code + " narrative is too thin to set a scene: " + text);
        }
    }

    @Test
    void narrativesNeverRepeatEachOtherOrTheEraName() {
        Set<String> seen = new HashSet<>();
        for (EraCode code : EraCode.values()) {
            String text = EraNarratives.narrative(code);
            assertTrue(seen.add(text), "duplicated era narrative for " + code + ": " + text);
            assertFalse(text.contains(code.getName()),
                    "narrative must not just restate the era title " + code.getName() + ": " + text);
        }
    }

    @Test
    void contentFileSectionsMatchEraCodesExactly() {
        Set<String> expected = Arrays.stream(EraCode.values())
                .map(EraCode::name)
                .collect(Collectors.toSet());
        assertEquals(expected, ContentCatalog.sections("era-narratives").keySet(),
                "era-narratives.txt groups must be the 11 EraCode names, no typos or extras");
    }

    @Test
    void lookupIsStableAcrossCalls() {
        Map<EraCode, String> all = EraNarratives.all();
        for (EraCode code : EraCode.values()) {
            assertEquals(all.get(code), EraNarratives.narrative(code),
                    "narrative must be a stable read-only value for " + code);
        }
    }
}
