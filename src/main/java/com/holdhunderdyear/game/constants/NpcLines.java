package com.holdhunderdyear.game.constants;

import com.holdhunderdyear.game.content.ContentCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 营业部老面孔的台词库（spec §7.7）：
 * <ul>
 *   <li>角色线场景台词（张大妈荐股、老李停车场、龙虎榜、割肉热议），替代原来
 *       "每个场景只有一句"的硬编码；{@code {stock}} 由 NpcService 换成真实票名；</li>
 *   <li>每位 NPC 的性格闲聊池，收盘后随机挑一位在场 NPC 说一句，
 *       让排行榜那几张老面孔真的"贯穿全剧"，而不是只在结算时冒出一个数字。</li>
 * </ul>
 *
 * <p>文案本体在 <code>resources/content/npc-lines.txt</code>，分组为
 * <code>@SCENE:场景名</code> 与 <code>@CHATTER:NPC代码</code>（代码取自
 * {@link NpcDefs} 花名册）。全部纯中文、无 emoji、不重复（由测试校验）。
 */
public final class NpcLines {

    private static final String FILE = "npc-lines";
    private static final String SCENE_PREFIX = "SCENE:";
    private static final String CHATTER_PREFIX = "CHATTER:";

    /** 角色线场景（与 NpcService 的触发点一一对应）。 */
    public enum Scene { DAMA_RECOMMEND, LAOLI_PARKING, LONGBOARD, CUT_LOSS }

    private static final Map<Scene, List<String>> SCENES = scenes();
    private static final Map<String, List<String>> CHATTER = chatter();

    private NpcLines() {
    }

    /** 取某场景的台词池（不会为空）。 */
    public static List<String> scene(Scene scene) {
        return SCENES.get(scene);
    }

    /** 取某位 NPC 的闲聊池；未知 code 返回空列表（调用方跳过，绝不影响结算）。 */
    public static List<String> chatter(String npcCode) {
        List<String> lines = CHATTER.get(npcCode);
        return lines == null ? List.of() : lines;
    }

    /** NPC 闲聊池（供测试遍历校验覆盖率与去重）。 */
    public static Map<String, List<String>> chatterPool() {
        return CHATTER;
    }

    private static Map<Scene, List<String>> scenes() {
        Map<Scene, List<String> > byScene = new LinkedHashMap<>();
        for (Scene scene : Scene.values()) {
            byScene.put(scene, ContentCatalog.require(FILE, SCENE_PREFIX + scene.name()));
        }
        return Map.copyOf(byScene);
    }

    private static Map<String, List<String>> chatter() {
        Map<String, List<String>> byNpc = new LinkedHashMap<>();
        for (NpcDefs.Npc npc : NpcDefs.ALL) {
            byNpc.put(npc.code(), ContentCatalog.require(FILE, CHATTER_PREFIX + npc.code()));
        }
        return Map.copyOf(byNpc);
    }
}
