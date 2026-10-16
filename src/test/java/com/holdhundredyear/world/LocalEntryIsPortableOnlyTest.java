package com.holdhundredyear.world;

import com.holdhundredyear.config.PortableDataSourceConfig;
import com.holdhundredyear.web.LocalGameController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 便携版入口是**免密发令牌**的，所以它绝不能出现在线上档里。这里用反射把"必须带
 * {@code @Profile("portable")}"钉死：Bean 不注册 → 没有 handler mapping →
 * {@code /api/auth/local/**} 真的 404，而不是"接口在、只是前端不显示"。
 *
 * <p>为什么不起一个默认档上下文来断言 Bean 不存在：那要多起一份 Spring 上下文，而且在全量
 * 测试带 {@code -Dspring.profiles.active=portable} 跑的那一轮里，默认档断言反而会被 profile
 * 打架带偏。反射查的是注解本身，两轮都成立，改 {@code @Profile} 时立刻红。
 */
class LocalEntryIsPortableOnlyTest {

    @Test
    void localEntryIsGatedToThePortableProfile() {
        assertPortableOnly(LocalGameController.class);
        assertPortableOnly(LocalPlayerService.class);
        // 顺手钉住便携档的数据源分支（它一旦变成常开，默认档会被 H2 顶掉真 MySQL）
        assertPortableOnly(PortableDataSourceConfig.class);
    }

    private static void assertPortableOnly(Class<?> type) {
        Profile profile = type.getAnnotation(Profile.class);
        assertNotNull(profile, type.getSimpleName() + " must be @Profile-gated, not always-on");
        assertTrue(Arrays.asList(profile.value()).contains("portable"),
                type.getSimpleName() + " must only load under the portable profile, got "
                        + Arrays.toString(profile.value()));
    }
}
