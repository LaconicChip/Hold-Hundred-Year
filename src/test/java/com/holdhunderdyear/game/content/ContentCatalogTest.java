package com.holdhunderdyear.game.content;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文案资源装载器的校验：资源目录里的每个 .txt 都必须已登记（漏登记=改了文件却不生效，
 * 是最难查的一种丢文案），且每个分组都必须非空、组内不得出现空白条目。
 */
class ContentCatalogTest {

    private static final Path CONTENT_DIR = Path.of("src/main/resources/content");

    @Test
    void everyContentFileIsRegistered() throws IOException {
        Set<String> onDisk;
        try (Stream<Path> files = Files.list(CONTENT_DIR)) {
            onDisk = files.map((path) -> path.getFileName().toString().replace(".txt", ""))
                    .collect(Collectors.toSet());
        }
        Set<String> loaded = ContentCatalog.loadedFiles();
        assertEquals(onDisk, loaded,
                "every file under src/main/resources/content must be listed in ContentCatalog.loadAll()");
        assertTrue(loaded.contains("danmu"), "sanity: danmu.txt should be loaded");
    }

    @Test
    void everySectionHasCleanEntries() {
        for (String file : ContentCatalog.loadedFiles()) {
            ContentCatalog.sections(file).forEach((section, lines) -> {
                assertTrue(!lines.isEmpty(), "empty section @" + section + " in " + file);
                for (String line : lines) {
                    assertTrue(!line.isBlank(), "blank entry in " + file + " / @" + section);
                    List<String> forbidden = List.of("TODO", "XXX", "占位");
                    for (String token : forbidden) {
                        assertTrue(!line.contains(token),
                                "placeholder text in " + file + " / @" + section + ": " + line);
                    }
                }
            });
        }
    }
}
