package com.holdhundredyear.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/**
 * "解压即玩"的最后一步：服务起来后自动开默认浏览器，双击图标 -> 直接进游戏。
 *
 * <p>开不了就退化成把地址打在控制台（无桌面环境、组策略禁用、或玩家自己用命令行跑的场景），
 * 绝不让"弹浏览器"这件事把游戏本身带崩。
 */
@Component
@Profile("portable")
class OpenGameInBrowserOnStartup implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(OpenGameInBrowserOnStartup.class);

    private final int port;

    OpenGameInBrowserOnStartup(@Value("${server.port:8080}") int port) {
        this.port = port;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String url = "http://localhost:" + port;
        log.info("游戏已就绪：{}（把 http://本机局域网IP:{} 发给同一网络里的朋友）", url, port);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                log.info("这台机器没给自动开浏览器的权限，手动打开 {} 即可", url);
            }
        } catch (Exception e) {
            log.info("自动开浏览器失败（{}），手动打开 {} 即可", e.getMessage(), url);
        }
    }
}
