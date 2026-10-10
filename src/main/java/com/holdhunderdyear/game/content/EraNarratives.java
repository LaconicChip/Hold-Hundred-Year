package com.holdhunderdyear.game.content;

import com.holdhunderdyear.game.model.enums.EraCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 11 个纪元的叙事文案（spec §4.3）：纪元切换通知的正文，一句话交代时代背景
 * （"低估值打底，券商先动""算力成了新石油"），让玩家知道风是从哪边吹来的。
 *
 * <p>文案本体在 <code>resources/content/era-narratives.txt</code>，分组名就是
 * {@link EraCode} 的枚举名（{@code @BULL_START}），**每组恰好一行**：纪元一多、
 * 或有人想给一个纪元写两句，都会在装载时直接抛错，而不是运行时随机挑一条
 * （这个字段不参与任何数值与随机协议，一条纪元只该有一句确定的正文）。
 */
public final class EraNarratives {

    private static final String FILE = "era-narratives";

    private static final Map<EraCode, String> NARRATIVES = load();

    private EraNarratives() {
    }

    /** 某纪元的叙事正文（11 个枚举全部有值，缺失在类加载时就抛了）。 */
    public static String narrative(EraCode code) {
        return NARRATIVES.get(code);
    }

    /** 全部纪元叙事，按枚举声明顺序（供测试遍历校验覆盖率与去重）。 */
    public static Map<EraCode, String> all() {
        return NARRATIVES;
    }

    private static Map<EraCode, String> load() {
        Map<EraCode, String> byEra = new LinkedHashMap<>();
        for (EraCode code : EraCode.values()) {
            List<String> lines = ContentCatalog.require(FILE, code.name());
            if (lines.size() != 1) {
                throw new IllegalStateException("era narrative must be exactly one line: "
                        + FILE + " / @" + code.name() + " has " + lines.size());
            }
            byEra.put(code, lines.get(0));
        }
        return Map.copyOf(byEra);
    }
}
