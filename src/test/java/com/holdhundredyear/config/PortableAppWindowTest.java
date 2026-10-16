package com.holdhundredyear.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * 绿色包最怕窗口顶部那条关不掉的提示条。实测（同一份 CfT，只改命令行）：不带
     * {@code --disable-infobars} 时窗口顶部钉着"Chrome 测试版仅适用于自动测试，请使用可自动更新
     * 的标准版 Chrome"，带上去就干净了——应用模式没有地址栏，这条提示又没有关闭按钮，玩家只能
     * 干瞪眼。另一条 {@code --simulate-outdated-no-au} 预防的是"构建过期后换成无法更新 Chrome"。
     * 同时保留独立 user-data-dir：少了它窗口会被甩进玩家已经开着的浏览器进程，"关窗即退出"失效。
     */
    @Test
    void appWindowArgumentsSuppressTheUndismissableUpdateNagAndKeepWindowOwnership() {
        List<String> args = PortableAppWindow.appWindowArguments(
                "D:/portable/browser/chrome.exe", "http://localhost:8080",
                Path.of("C:/Users/friend/.hold-hundred-year/profile/chrome.exe"));

        assertEquals("D:/portable/browser/chrome.exe", args.get(0), "the executable must stay the first token");
        assertTrue(args.contains("--app=http://localhost:8080"), "must open an app window, not another tab");
        assertTrue(args.stream().anyMatch(a -> a.startsWith("--user-data-dir=")),
                "must own its profile dir, otherwise the window is handed to the player's browser process");
        assertTrue(args.stream().anyMatch(a -> a.startsWith("--simulate-outdated-no-au=")),
                "must tell Chrome for Testing it is not outdated, or the window shows an undismissable update bar");
        assertTrue(args.contains("--disable-infobars"),
                "must drop the Chrome-for-Testing automated-testing banner, which has no close button");
    }

    @Test
    void noBrowserAnywhereReturnsNullSoTheCallerCanDegrade() {
        assertNull(PortableAppWindow.resolveBrowserPath("", path -> false),
                "null means 'degrade to the default browser', never 'crash'");
    }
}
