package com.holdhundredyear.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * "解压即玩"的窗口层：把游戏开成一个**独立应用窗口**，而不是玩家浏览器里的又一个标签页。
 *
 * <p>做法是用系统里必装的 Edge/Chrome 的应用模式（`--app=`：无地址栏、无书签栏、独立任务栏
 * 分组），并给它一个专属 user-data-dir——既保证窗口是我们自己的独立进程（否则浏览器会把新窗口
 * 甩给玩家已经开着的那个进程，我们既拿不到关闭事件，也会污染他的书签和登录态），也让
 * "关掉这个窗口 = 退出游戏"成立。
 *
 * <p>浏览器挑选顺序：<b>包内自带的 `browser\chrome.exe`</b> → 配置指定（`portable.browser-path`）
 * → 系统 Edge/Chrome。自带那份是实测出来的必需品：
 * <ul>
 *   <li>自带 Chrome for Testing：浏览器进程由我们 spawn 并持有，关窗 → 进程结束 → 守护线程走
 *       Spring 正常关闭 → 服务器退出，整条链实测成立；CfT 是 Google 给"随应用分发"做的构建，
 *       静态链接 CRT，对方不用装 VC++ 运行库（Playwright 的自编译 Chromium 就要求装，实测在未装
 *       运行库的机器上 CreateProcess 14001 起不来）。</li>
 *   <li>系统 Edge：有"启动加速"，会把新窗口交给后台已有的 Edge 进程，我们 spawn 的进程 0 秒退出，
 *       "关窗退出"不可靠（窗口照常，只能靠 `停止游戏.bat`）。</li>
 * </ul>
 * 所以绿色包默认自带浏览器；不带也能玩，只是退出方式退化。
 *
 * <p>三条降级路径都必须安静：找不到浏览器 → 退回默认浏览器；连默认浏览器也开不了 → 只把地址
 * 打在控制台；窗口"秒退"（多半是已经有一个游戏窗口开着）→ 服务器继续跑，绝不自杀。
 */
@Component
@Profile("portable")
class PortableAppWindow implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(PortableAppWindow.class);

    /** Windows 10/11 上 Edge 必在；Chrome 装了就优先用它的应用模式。 */
    private static final List<String> BROWSER_CANDIDATES = List.of(
            "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
            "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
            "C:/Program Files/Google/Chrome/Application/chrome.exe",
            "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe");

    /** 窗口活过这些秒数才算"玩家真的在玩"；否则视为误启动，不能顺手把服务器带走。 */
    static final long MIN_ALIVE_SECONDS = 5;

    private final int port;
    private final String browserPathOverride;
    private final boolean quitWithWindow;
    private final ConfigurableApplicationContext context;

    PortableAppWindow(@Value("${server.port:8080}") int port,
                      @Value("${portable.browser-path:}") String browserPathOverride,
                      @Value("${portable.quit-with-window:true}") boolean quitWithWindow,
                      ConfigurableApplicationContext context) {
        this.port = port;
        this.browserPathOverride = browserPathOverride;
        this.quitWithWindow = quitWithWindow;
        this.context = context;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 只有真的起了 Web 服务器才弹窗口：local.server.port 是真实启动监听后才写进环境的，
        // 测试（MockMvc 的 MOCK web 环境）与"只跑命令行工具"都没有它——否则一次全量测试会在
        // 开发机上开出一排浏览器窗口。用这个判据而不是 instanceof 某个 Web 上下文类：
        // Boot 4 把相关类拆进了别的模块，包名会变。
        if (event.getApplicationContext().getEnvironment().getProperty("local.server.port") == null) {
            return;
        }
        String url = "http://localhost:" + port;
        log.info("game is up at {} (LAN friends: http://<this-machine-ip>:{})", url, port);

        String browser = resolveBrowserPath(browserPathOverride, PortableAppWindow::isFile);
        if (browser == null) {
            openWithDefaultBrowser(url);
            return;
        }
        try {
            launchAppWindow(browser, url);
        } catch (IOException e) {
            log.info("app window failed to start ({}), falling back to the default browser", e.getMessage());
            openWithDefaultBrowser(url);
        }
    }

    private void launchAppWindow(String browser, String url) throws IOException {
        // 每种浏览器一个独立配置目录：实测 Edge 与 Chrome 共用同一个 user-data-dir 时，后启动的
        // 会把窗口交给先启动的实例，我们的子进程 0 秒退出，"关窗即退出"当场失效。
        String browserName = Path.of(browser).getFileName().toString().toLowerCase();
        Path profile = Path.of(System.getProperty("user.home"), ".hold-hundred-year", "profile", browserName);
        Files.createDirectories(profile);
        List<String> command = new ArrayList<>(List.of(
                browser,
                "--app=" + url,
                "--user-data-dir=" + profile.toAbsolutePath(),
                "--class=hold-hundred-year",
                "--no-first-run",
                "--no-default-browser-check"));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        log.info("game window opened ({}); closing it quits the game", Path.of(browser).getFileName());
        // 正常退出（Ctrl+C、关窗自杀式退出之外的路径）时顺手收掉自己开的浏览器窗口，
        // 免得留一个显示"连不上"的页面对玩家说"游戏没退"。注意：只处理我们 spawn 的那个进程，
        // 绝不做按映像名 taskkill —— 自带浏览器也叫 chrome.exe，会误杀玩家自己的 Chrome。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                process.destroy();
            }
        }, "portable-window-cleanup"));
        if (quitWithWindow) {
            watchWindowAndExit(process);
        }
    }

    /** 守护线程：等窗口进程结束，再走 Spring 的正常关闭流程（H2 落盘、连接池收摊）。 */
    private void watchWindowAndExit(Process process) {
        long startedAt = System.nanoTime();
        Thread watcher = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long aliveSeconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
            if (aliveSeconds < MIN_ALIVE_SECONDS) {
                log.info("the browser process handed off and exited after {}s (system Edge keeps its own "
                        + "background process, so closing the window cannot stop the game here); "
                        + "keeping the server up - use stop-game.bat or close this console to quit", aliveSeconds);
                return;
            }
            log.info("game window closed, shutting the local server down");
            System.exit(SpringApplication.exit(context));
        }, "portable-window-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void openWithDefaultBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            log.info("default browser could not be opened ({})", e.getMessage());
        }
        log.info("open {} manually in any browser", url);
    }

    /**
     * 挑一个可用的浏览器可执行文件：配置里显式给的排最前（朋友装在非标准路径时用
     * `portable.browser-path` 指过来），其后是 Edge / Chrome 的常见位置。
     * 返回 null 表示都没找到，调用方负责降级。
     */
    static String resolveBrowserPath(String override, Predicate<String> isFile) {
        String bundled = bundledBrowserPath();
        if (isFile.test(bundled)) {
            return bundled;
        }
        if (override != null && !override.isBlank() && isFile.test(override)) {
            return override;
        }
        for (String candidate : BROWSER_CANDIDATES) {
            if (isFile.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 绿色包目录下的自带浏览器（`browser\chrome.exe`，由 pack-portable.cmd 放进去）。 */
    static String bundledBrowserPath() {
        return Path.of(System.getProperty("user.dir"), "browser", "chrome.exe").toString();
    }

    private static boolean isFile(String path) {
        try {
            return Files.isRegularFile(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
