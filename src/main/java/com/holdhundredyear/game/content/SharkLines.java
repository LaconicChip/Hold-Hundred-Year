package com.holdhundredyear.game.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 高利贷板块的台词（spec §6.6）：借到手、每日催债、到期催收、逾期、强卖、结清，
 * 以及板块常驻告示。文案本体在 <code>resources/content/shark-lines.txt</code>。
 *
 * <p>只出话、不出数：额度倍数、日息档位、到期日这些真值由接口下发、前端渲染，
 * 免得改了常量还要回来改文案。
 */
public final class SharkLines {

    private static final String FILE = "shark-lines";

    /** 台词场景，与 txt 里的 {@code @分组名} 一一对应。 */
    public enum Scene { BORROWED, CHATTER, DUE_SOON, DUE_TODAY, OVERDUE, LIQUIDATED, CLEARED, WARNING }

    private static final Map<Scene, List<String>> POOLS = pools();

    private SharkLines() {
    }

    /** 某场景的台词池（不会为空）。 */
    public static List<String> pool(Scene scene) {
        return POOLS.get(scene);
    }

    /** 全部台词池（供测试遍历校验覆盖率与去重）。 */
    public static Map<Scene, List<String>> all() {
        return POOLS;
    }

    /**
     * 从某场景抽一条。随机数由调用方传入——结算路径必须用
     * {@code WorldRandom.of(worldSeed, "shark" + day)}，同种子同日必得同一条话。
     */
    public static String pick(Scene scene, Random rng) {
        List<String> lines = pool(scene);
        return lines.get(rng.nextInt(lines.size()));
    }

    private static Map<Scene, List<String>> pools() {
        Map<Scene, List<String>> byScene = new LinkedHashMap<>();
        for (Scene scene : Scene.values()) {
            byScene.put(scene, ContentCatalog.require(FILE, scene.name()));
        }
        return Map.copyOf(byScene);
    }
}
