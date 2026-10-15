package com.holdhundredyear.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 前端与后端同一个 jar 时的静态资源与 SPA 路由兜底。
 *
 * <p>打包流程把 {@code frontend/dist} 拷进 {@code src/main/resources/static/}，Spring Boot
 * 直接把它作为站点根目录发出去，朋友只需要一个地址、不需要再开 vite。
 *
 * <p>但前端路由用的是 history 模式（{@code createWebHistory}）：浏览器直接访问 /market、
 * /stock/3 或在这些页面上刷新时，后端并没有这些接口，默认会 404。这里把"不是真实文件、
 * 也不是后端接口"的 GET 请求一律交给 index.html，由前端路由自己接管。
 *
 * <p>/api 与 /ws 前缀必须原样落空（返回 null 交回给 DispatcherServlet），否则接口报错
 * 会被伪装成"首页能打开"，排查起来非常费劲。
 */
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    private static final String INDEX = "/static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }

    /** 真实文件优先；否则回 index.html；后端自己的路径不回。 */
    static class SpaResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws/")
                    || resourcePath.startsWith("error")) {
                return null;
            }
            Resource index = new ClassPathResource(INDEX);
            return index.exists() ? index : null;
        }
    }
}
