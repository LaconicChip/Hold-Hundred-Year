package com.holdhundredyear.config;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 便携包的单实例闸门（spec §12 / implementation-notes §4）。
 *
 * <p>玩家最常见的一类 "Failed to launch JVM" 不是 JVM 坏了，而是<b>又点了一次图标</b>：
 * 第二份进程启动时抢 H2 文件锁失败（`Database may be already in use`），Spring 起不来，
 * jpackage 启动器只弹一句没头没尾的 "Failed to launch JVM"。这里在 Spring 启动之前先判断：
 * <ul>
 *   <li>同端口已有服务在应答：说明游戏已经在跑，直接把窗口打开，第二个进程安静退出；</li>
 *   <li>同一份存档目录已有实例持有文件锁：等它把 HTTP 服务拉起来再开窗，绝不抢 H2；</li>
 *   <li>真的启动失败：在 data 目录写一份中文说明，并弹窗告诉玩家日志在哪。</li>
 * </ul>
 *
 * <p>文件锁在进程存活期间一直持有，进程退出（含被任务管理器结束）由操作系统释放；
 * 残留的 lock 文件不影响下一次启动。
 */
public final class PortableInstanceGuard {

    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_H2_PATH = "./data/hold_year";
    private static final String LOCK_FILE = "instance.lock";
    private static final String FAILURE_FILE = "启动失败.txt";
    private static final int WAIT_ROUNDS = 50;
    private static final long WAIT_INTERVAL_MS = 300;

    private static FileChannel lockChannel;
    private static FileLock fileLock;

    private PortableInstanceGuard() {
    }

    /**
     * Spring 启动前的闸门。返回 true 表示"已经有一个实例在跑，本进程不该再启动"。
     * 只在 portable 档生效，开发/MySQL 档保持原行为。
     */
    public static boolean exitIfAlreadyRunning() {
        String profile = System.getProperty("spring.profiles.active",
                System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", ""));
        if (!isPortable(profile)) {
            return false;
        }
        int port = portOf(System.getenv("HOLD_PORT"));
        // 先探 HTTP：第一个实例可能已经跑起来，只是玩家又点了一次图标。
        if (serverUp(port)) {
            openBrowser(port);
            return true;
        }
        Path dataDir = dataDirOf(System.getenv("HOLD_H2_PATH"));
        try {
            Files.createDirectories(dataDir);
            FileChannel channel = FileChannel.open(dataDir.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired = tryLock(channel);
            if (acquired == null) {
                channel.close();
                // 另一份进程正在启动或已经启动：等它把服务拉起来再开窗。
                if (waitForServer(port)) {
                    openBrowser(port);
                } else {
                    showMessage("游戏似乎已经在启动中。请稍等几秒再试；"
                            + "如果一直打不开，请查看 data\\hold-year.log。");
                }
                return true;
            }
            lockChannel = channel;
            fileLock = acquired;
            Runtime.getRuntime().addShutdownHook(new Thread(PortableInstanceGuard::release,
                    "portable-instance-lock"));
            return false;
        } catch (IOException e) {
            // 锁文件不可写（目录权限等）不该阻止游戏启动：退回 HTTP 探测。
            if (serverUp(port)) {
                openBrowser(port);
                return true;
            }
            return false;
        }
    }

    /** 启动失败时给玩家一份能看懂的说明（jpackage 的错误框只有一句 Failed to launch JVM）。 */
    public static void reportStartupFailure(Throwable error) {
        String profile = System.getProperty("spring.profiles.active",
                System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", ""));
        if (!isPortable(profile)) {
            return;
        }
        Path dataDir = dataDirOf(System.getenv("HOLD_H2_PATH"));
        String detail = error == null ? "unknown" : error.toString();
        Path note = dataDir.resolve(FAILURE_FILE);
        try {
            Files.createDirectories(dataDir);
            Files.writeString(note,
                    "游戏启动失败（" + LocalDateTime.now() + "）\n\n"
                            + detail + "\n\n"
                            + "排查顺序：\n"
                            + "1. 确认游戏目录完整解压，没有直接从压缩包里双击；\n"
                            + "2. 确认没有已经开着的游戏窗口（不要重复双击图标）；\n"
                            + "3. 打开同目录的 hold-year.log，把最后一段报错发回给作者。\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 连说明都写不进去时，至少把路径弹出来
        }
        showMessage("游戏启动失败。\n\n"
                + "请先确认游戏已经完整解压，并且没有重复打开。\n"
                + "详细原因已写入：data\\" + FAILURE_FILE + "\n"
                + "日志：data\\hold-year.log");
    }

    static boolean isPortable(String profile) {
        return profile != null && profile.contains("portable");
    }

    static int portOf(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(text.trim());
            return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    static Path dataDirOf(String h2Path) {
        String path = h2Path == null || h2Path.isBlank() ? DEFAULT_H2_PATH : h2Path.trim();
        Path parent = Path.of(path).getParent();
        return parent == null ? Path.of(".") : parent;
    }

    /** 探测本机服务；任何 HTTP 应答（含 401）都说明服务已经起来了。 */
    static boolean serverUp(int port) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/saves"))
                .timeout(Duration.ofMillis(800))
                .GET()
                .build();
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static boolean waitForServer(int port) {
        for (int i = 0; i < WAIT_ROUNDS; i++) {
            if (serverUp(port)) {
                return true;
            }
            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void openBrowser(int port) {
        String url = "http://localhost:" + port;
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // 走下面的兜底
        }
        try {
            Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
        } catch (IOException ignored) {
            // 实在打不开就安静退出，玩家可以手动访问地址
        }
    }

    private static void showMessage(String text) {
        try {
            JOptionPane.showMessageDialog(null, text, "你持有一百年看看", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ignored) {
            // 没有图形环境时不影响进程退出
        }
    }

    private static void release() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ignored) {
            // 进程退出时操作系统会兜底释放
        }
    }
}
