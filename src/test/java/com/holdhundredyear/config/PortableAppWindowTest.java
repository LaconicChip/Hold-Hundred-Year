package com.holdhundredyear.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 浏览器挑选逻辑（纯函数，不碰文件系统）：绿色包靠它把游戏开成独立应用窗口。
 * 顺序错了会让朋友点开一个 IE，或者明明装了 Chrome 却找不到窗口。
 */
class PortableAppWindowTest {

    @Test
    void explicitOverrideWinsWhenItExists() {
        String picked = PortableAppWindow.resolveBrowserPath("D:/portable/chrome.exe",
                path -> path.equals("D:/portable/chrome.exe"));
        assertEquals("D:/portable/chrome.exe", picked, "portable.browser-path must be tried first");
    }

    /** 包里自带浏览器时必须第一个用它：只有自己的浏览器进程才关得掉、退得出。 */
    @Test
    void bundledBrowserInThePackageWinsOverSystemBrowsers() {
        String bundled = PortableAppWindow.bundledBrowserPath();
        String picked = PortableAppWindow.resolveBrowserPath(
                "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                path -> path.equals(bundled));
        assertEquals(bundled, picked, "browser/chrome.exe shipped in the package must win");
    }

    @Test
    void blankOrMissingOverrideFallsBackToTheKnownLocations() {
        List<String> present = List.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe");
        assertEquals(present.get(0),
                PortableAppWindow.resolveBrowserPath("", present::contains),
                "blank override must not disable auto-detection");
        assertEquals(present.get(0),
                PortableAppWindow.resolveBrowserPath("C:/nowhere/chrome.exe", present::contains),
                "a wrong override must fall through to auto-detection instead of killing the window");
    }

    @Test
    void noBrowserAnywhereReturnsNullSoTheCallerCanDegrade() {
        assertNull(PortableAppWindow.resolveBrowserPath("", path -> false),
                "null means 'degrade to the default browser', never 'crash'");
    }
}
