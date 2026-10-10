package com.holdhundredyear.game.content;


import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.content.ContentCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 24 只股票的"人设档案"（spec §7.1/§7.3）：绰号、股性、专属弹幕池、专属事件文案池。
 * 存在的意义是让每只票有自己的说话方式——股吧里怎么叫它、它抽风时什么脾气、
 * 出利好出利空分别长什么样，而不是所有票共用一套通用文案。
 *
 * <p>文案本体在 <code>resources/content/stock-personas.txt</code>（分组形如
 * <code>@1:DANMU</code>），本类只负责按 {@link StockDefs} 的 id 组装成带类型的档案；
 * 新增一只票 = 档案表加一行 + 文案文件加四组，不碰任何引擎代码。
 *
 * <p>消费方：{@code DanmuService} 命中个股行情时按"专属池 → 板块池 → 全局池"三级取文案；
 * {@code EventFactory} 在 STOCK 级事件命中目标股时用专属事件文案替换通用正文
 * （沿用事件骨架的槽位白名单，{@code {stock}} 渲染成股票名）。
 */
public final class StockPersonas {

    private static final String FILE = "stock-personas";

    /** 单只股票的人设。danmu/news 均非空。 */
    public record Persona(long stockId, String nickname, String temper,
                          List<String> danmu, List<String> news) {
    }

    public static final List<Persona> ALL = build();

    private static final Map<Long, Persona> BY_ID;

    static {
        Map<Long, Persona> byId = new LinkedHashMap<>();
        for (Persona persona : ALL) {
            byId.put(persona.stockId(), persona);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private StockPersonas() {
    }

    /** 按 id 取人设；未知 id 属编程错误（人设必须与 StockDefs 一一对应），直接抛。 */
    public static Persona byId(long stockId) {
        Persona persona = BY_ID.get(stockId);
        if (persona == null) {
            throw new IllegalArgumentException("No persona for stockId: " + stockId);
        }
        return persona;
    }

    public static boolean has(long stockId) {
        return BY_ID.containsKey(stockId);
    }

    private static List<Persona> build() {
        List<Persona> personas = new ArrayList<>(StockDefs.ALL.size());
        for (StockDefs.StockDef def : StockDefs.ALL) {
            String id = String.valueOf(def.id());
            personas.add(new Persona(def.id(),
                    one(FILE, id + ":NICKNAME", def.name()),
                    one(FILE, id + ":TEMPER", ""),
                    ContentCatalog.require(FILE, id + ":DANMU"),
                    ContentCatalog.require(FILE, id + ":NEWS")));
        }
        return List.copyOf(personas);
    }

    private static String one(String file, String section, String fallback) {
        List<String> lines = ContentCatalog.require(file, section);
        return lines.isEmpty() ? fallback : lines.get(0);
    }
}
