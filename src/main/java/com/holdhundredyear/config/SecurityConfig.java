package com.holdhundredyear.config;

import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.R;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 安全配置：无状态 JWT。鉴权面只有一句话——**除 /api/auth/** 外的 /api/**** 要登录**。
 *
 * <p>其余请求一律放行，因为它们是前端页面与静态资源：整局游戏打包成单个 jar 后，
 * 浏览器直接访问 /market、/stock/3 这类 history 路由，或刷新页面，都会打到后端来
 * 要 index.html（见 {@link WebStaticConfig}）。以前写的是 anyRequest().authenticated()，
 * 结果首页和 js/css 全被挡成 401，jar 里嵌的前端根本打不开。
 *
 * <p>反过来，新增后端接口只要落在 /api/ 下就自动受保护，不需要回来改这张表；
 * 真正的鉴权细节（token 解析、越权）在业务层与 {@link JwtAuthFilter}。
 * WebSocket 握手放行是既有的：/ws 的鉴权在 STOMP CONNECT 帧上带 token（WebSocketConfig）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(MAPPER.writeValueAsString(R.fail(ErrorCode.UNAUTHORIZED)));
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
