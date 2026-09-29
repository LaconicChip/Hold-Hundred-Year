package com.holdhunderdyear.game.content;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文案资源目录：把"说什"从 Java 里搬到 <code>src/main/resources/content/*.txt</code>，
 * 改文案不再动代码、不再重新理解引擎，也不会因为改一句话去碰编译期常量。
 *
 * <p>文件格式（人写人读，故意不用 JSON/YAML 那套转义地狱）：
 * <pre>
 * # 井号行是注释，空行忽略
 * @分组名
 * 一行一条文案
 * </pre>
 *
 * <p>一次性加载并缓存（内容只读、进程内不变）；文件缺失、分组缺失或分组为空都
 * 直接抛 IllegalStateException —— 文案漏了要在启动时就炸，而不是运行时悄悄返回空列表。
 */
public final class ContentCatalog {

    private static final String DIR = "content/";
    private static final Map<String, Map<String, List<String>>> FILES = loadAll();

    private ContentCatalog() {
    }

    /** 某个文件的全部分组（只读、保持文件里的书写顺序）。 */
    public static Map<String, List<String>> sections(String file) {
        Map<String, List<String>> bySection = FILES.get(file);
        if (bySection == null) {
            throw new IllegalStateException("missing content file: " + DIR + file + ".txt");
        }
        return bySection;
    }

    /** 某个分组的全部文案；分组不存在或为空即抛（宁可启动失败，也不要运行时静默无文案）。 */
    public static List<String> require(String file, String section) {
        List<String> lines = sections(file).get(section);
        if (lines == null || lines.isEmpty()) {
            throw new IllegalStateException(
                    "content section is empty or missing: " + file + " / @" + section);
        }
        return lines;
    }

    /** 某个分组下所有以 prefix 开头的分组名（如按 "@板块:类别" 组织的文件）。 */
    public static Set<String> sectionsWithPrefix(String file, String prefix) {
        return sections(file).keySet().stream()
                .filter((key) -> key.startsWith(prefix))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static Map<String, Map<String, List<String>>> loadAll() {
        Map<String, Map<String, List<String>>> loaded = new LinkedHashMap<>();
        for (String file : List.of("danmu", "stock-personas", "sector-flavor", "npc-lines")) {
            loaded.put(file, parse(file));
        }
        return Collections.unmodifiableMap(loaded);
    }

    private static Map<String, List<String>> parse(String file) {
        Map<String, List<String>> bySection = new LinkedHashMap<>();
        String path = DIR + file + ".txt";
        try (InputStream in = ContentCatalog.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("content resource not found on classpath: " + path);
            }
            String section = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    if (trimmed.startsWith("@")) {
                        section = trimmed.substring(1).trim();
                        bySection.putIfAbsent(section, new ArrayList<>());
                        continue;
                    }
                    if (section == null) {
                        throw new IllegalStateException(path + ": content line before any @section: " + trimmed);
                    }
                    bySection.get(section).add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read content resource " + path, e);
        }
        bySection.replaceAll((key, lines) -> List.copyOf(lines));
        return Collections.unmodifiableMap(bySection);
    }
}
