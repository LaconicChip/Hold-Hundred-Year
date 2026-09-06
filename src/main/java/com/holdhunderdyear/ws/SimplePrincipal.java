package com.holdhunderdyear.ws;

import java.security.Principal;

/**
 * STOMP 会话 Principal（Task 10.2，spec §9 握手鉴权）：仅承载 userId，由 JWT 握手拦截器在
 * CONNECT 时建立；{@code getName()} 以 userId 的字符串呈现，Spring STOMP 据此做 per-user
 * 队列路由（/user/{userId}/queue/*）。record 值语义，天然 JSON/往返安全。
 */
public record SimplePrincipal(Long userId) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}