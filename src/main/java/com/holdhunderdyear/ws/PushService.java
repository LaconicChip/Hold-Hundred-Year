package com.holdhunderdyear.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 实时推送服务（Phase 8.3 最小占位 → Task 10.2 扩展）。当前仅提供弹幕推送方法
 * {@link #pushDanmu} 的方法签名与空实现，使 8.3 的弹幕链路（DanmuService 经
 * ObjectProvider 软依赖投递）可编译运行；正式 STOMP 通道与在线会话下发由 Task 10.2
 * 基于 {@link WsSessionRegistry} 在此扩展。弹幕文案本身由前端窗口渲染，本类只负责投递。
 */
@Slf4j
@Service
public class PushService {

    /** 下发一条弹幕文案给某用户（Phase 10.2 接入 STOMP 通道；当前占位仅记日志）。 */
    public void pushDanmu(long userId, String danmu) {
        log.debug("danmu push placeholder: userId={}, text={}", userId, danmu);
    }
}