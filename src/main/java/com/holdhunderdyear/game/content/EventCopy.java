package com.holdhunderdyear.game.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件骨架的文案池：从 <code>resources/content/event-copy.txt</code> 读出每条骨架的
 * 标题/正文变体与证实-辟谣追报，供 {@code EventSkeletons} 组装骨架时按编号取用。
 *
 * <p>一行一条，格式（详见文件头注释）：
 * <pre>
 * 标题|正文                          通用变体
 * sector:板块名|标题|正文             板块专属变体（仅 SECTOR 骨架）
 * &gt;追报正文                         传闻被证实/辟谣时追加的消息文案
 * </pre>
 *
 * <p>这里不引用任何领域类型（返回自己的 {@link Item}），保持"文案层"零业务依赖；
 * 板块名是否合法由 EventSkeletonsTest 对着 StockDefs 校验。
 */
public final class EventCopy {

    private static final String FILE = "event-copy";
    private static final String SECTOR_MARK = "sector:";
    private static final String FOLLOWUP_MARK = ">";

    /** 一条变体：sector 为 null 表示不限板块。 */
    public record Item(String sector, String title, String text) {
    }

    /** 一条骨架的全部文案。 */
    public record Pools(List<Item> items, List<String> followups) {
    }

    private static final Map<String, Pools> BY_CODE = load();

    private EventCopy() {
    }

    /** 按骨架编号取文案；编号缺失直接抛（骨架表与文案文件必须一一对应，由测试兜底）。 */
    public static Pools of(String code) {
        Pools pools = BY_CODE.get(code);
        if (pools == null) {
            throw new IllegalStateException("missing event copy section: @" + code);
        }
        return pools;
    }

    /** 文案文件里的全部编号（供测试与骨架表互相校验）。 */
    public static Set<String> codes() {
        return Collections.unmodifiableSet(BY_CODE.keySet());
    }

    private static Map<String, Pools> load() {
        Map<String, Pools> byCode = new LinkedHashMap<>();
        ContentCatalog.sections(FILE).forEach((code, rawLines) -> byCode.put(code, parse(code, rawLines)));
        return Collections.unmodifiableMap(byCode);
    }

    private static Pools parse(String code, List<String> rawLines) {
        List<Item> items = new ArrayList<>();
        List<String> followups = new ArrayList<>();
        for (String raw : rawLines) {
            if (raw.startsWith(FOLLOWUP_MARK)) {
                followups.add(requireText(code, raw.substring(FOLLOWUP_MARK.length()), "followup"));
                continue;
            }
            String[] parts = raw.split("\\|");
            if (parts[0].startsWith(SECTOR_MARK)) {
                if (parts.length != 3) {
                    throw new IllegalStateException("bad sector copy line in @" + code + ": " + raw);
                }
                items.add(new Item(requireText(code, parts[0].substring(SECTOR_MARK.length()), "sector"),
                        requireText(code, parts[1], "title"), requireText(code, parts[2], "text")));
            } else if (parts.length == 2) {
                items.add(new Item(null, requireText(code, parts[0], "title"),
                        requireText(code, parts[1], "text")));
            } else {
                throw new IllegalStateException("bad copy line in @" + code + " (want title|text): " + raw);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("event copy section has no variant: @" + code);
        }
        return new Pools(List.copyOf(items), List.copyOf(followups));
    }

    private static String requireText(String code, String value, String what) {
        String text = value.trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("blank " + what + " in event copy section @" + code);
        }
        return text;
    }
}
