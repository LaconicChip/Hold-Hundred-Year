package com.holdhundredyear.config;

import com.holdhundredyear.common.JwtUtil;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.mapper.SysUserMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：解析 Bearer token，成功则把 userId 放入 SecurityContext；
 * 解析失败或<b>令牌签发的用户（存档）已经不存在</b>时清空上下文（由 entry point 统一返回 401）；
 * /api/saves/** 直接跳过——存档架本身不需要登录。
 *
 * <p>"用户还在不在"这一步不能省：便携版换包、删掉 data\\ 存档、恢复旧档之后，浏览器里
 * 还留着上一个用户签发的令牌。以前它会被当成合法身份放过，一路走到行情板加载才炸成 500；
 * 现在当场判未登录，前端拦截器清掉 token 把玩家送回入口页，点"继续游戏"或"创建新游戏"
 * 就能自愈。只在 /api/** 上查这一次主键（静态资源不查），单机/局域网几人规模可忽略。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    private final JwtUtil jwtUtil;
    private final SysUserMapper sysUserMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, SysUserMapper sysUserMapper) {
        this.jwtUtil = jwtUtil;
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/saves/")
                || request.getRequestURI().equals("/api/saves");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Long userId = jwtUtil.parseUserId(header.substring("Bearer ".length()));
                if (userId != null && userStillExists(request, userId)) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, List.of()));
                } else {
                    SecurityContextHolder.clearContext();
                }
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 接口请求才校验用户存在（静态资源不查库，避免每个 js/css 多一次主键查询）。 */
    private boolean userStillExists(HttpServletRequest request, long userId) {
        if (!request.getRequestURI().startsWith(API_PREFIX)) {
            return true;
        }
        return sysUserMapper.selectById(userId) != null;
    }
}
