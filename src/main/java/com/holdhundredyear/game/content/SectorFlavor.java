package com.holdhundredyear.game.content;


import com.holdhundredyear.game.content.ContentCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 板块级与全市场级消息文案库（spec §7.1/§7.3）：每个板块各有权威公告、媒体报道、
 * 股吧传闻（含配套的证实/辟谣追报）与玩梗段子，让"白酒的新闻"和"钢铁的新闻"
 * 听起来不像同一段模板换了个名字。
 *
 * <p>文案本体在 <code>resources/content/sector-flavor.txt</code>，分组形如
 * <code>@白酒:RUMOR</code>，每条格式 <code>标题|正文[|追报]</code>（第三段用于三态揭晓时
 * 的追报正文，保证"辟谣的话"和"当初的谣"讲的是同一件事）。
 *
 * <p>消费方：{@code EventFactory} 在 SECTOR/MARKET 级事件上按来源取对应池子，
 * 命中时成对替换标题与正文（MARKET 级与未知板块走 {@link #MARKET} 池）。
 */
public final class SectorFlavor {

    private static final String FILE = "sector-flavor";

    /** 全市场级文案的归属键（不是真实板块，用于 MARKET 级事件与兜底）。 */
    public static final String MARKET = "大盘";

    /** 消息来源类别：与 EventSkeleton.Source 的对应关系见 EventFactory。 */
    public enum Channel { AUTHORITY, MEDIA, RUMOR, FUN }

    /** 一条板块文案：后续（reveal）仅传闻类填充，用于证实/辟谣追报。 */
    public record Entry(String title, String text, String reveal) {
    }

    private static final Map<String, Map<Channel, List<Entry>>> BY_SECTOR = load();

    private SectorFlavor() {
    }

    /** 取某板块某类别的文案；板块未知时回退全市场池（绝不返回空，调用方直接抽）。 */
    public static List<Entry> pool(String sector, Channel channel) {
        Map<Channel, List<Entry>> byChannel = BY_SECTOR.get(sector);
        if (byChannel == null) {
            byChannel = BY_SECTOR.get(MARKET);
        }
        List<Entry> pool = byChannel == null ? null : byChannel.get(channel);
        return pool != null && !pool.isEmpty() ? pool : BY_SECTOR.get(MARKET).get(channel);
    }

    /** 全部板块键（含全市场），供测试遍历校验覆盖率。 */
    public static Set<String> sectors() {
        return BY_SECTOR.keySet();
    }

    private static Map<String, Map<Channel, List<Entry>>> load() {
        Map<String, Map<Channel, List<Entry>>> bySector = new LinkedHashMap<>();
        for (String key : ContentCatalog.sections(FILE).keySet()) {
            int split = key.indexOf(':');
            if (split <= 0) {
                throw new IllegalStateException("bad section in " + FILE + ": " + key);
            }
            String sector = key.substring(0, split);
            Channel channel = Channel.valueOf(key.substring(split + 1));
            List<Entry> entries = ContentCatalog.require(FILE, key).stream().map(SectorFlavor::parse).toList();
            bySector.computeIfAbsent(sector, k -> new LinkedHashMap<>()).put(channel, entries);
        }
        Map<String, Map<Channel, List<Entry>>> frozen = new LinkedHashMap<>();
        bySector.forEach((sector, channels) -> frozen.put(sector, Map.copyOf(channels)));
        return Map.copyOf(frozen);
    }

    /** 解析 <code>标题|正文[|追报]</code>。 */
    private static Entry parse(String raw) {
        String[] parts = raw.split("\\|");
        String title = parts.length > 0 ? parts[0].trim() : "";
        String text = parts.length > 1 ? parts[1].trim() : "";
        String reveal = parts.length > 2 ? parts[2].trim() : null;
        return new Entry(title, text, reveal);
    }
}
