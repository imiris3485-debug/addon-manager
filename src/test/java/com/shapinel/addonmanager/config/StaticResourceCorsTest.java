package com.shapinel.addonmanager.config;

import com.shapinel.addonmanager.security.config.SecurityConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증하는 정책:
 * - 프론트 정적 파일(/assets/**)은 로그인 없이 받을 수 있어야 한다.
 * - 프록시(Cloudflare Tunnel) 뒤에서는 앱이 요청을 http 로 보지만 브라우저의 Origin 은 https 다.
 *   이때 같은 사이트의 정적 파일 요청이 CORS 검사에 걸려 403 이 되면 안 된다.
 */
class StaticResourceCorsTest {

    private static final String SITE = "https://addon-manager.shapinel.com";

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, SpaWebConfig.class})
    static class TestConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager();
        }
    }

    private AnnotationConfigWebApplicationContext context;

    private MockMvc mvc(String allowedOrigins) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "app.jwt.access-secret", "0123456789012345678901234567890123456789",
                "app.cors.allowed-origins", allowedOrigins)));
        context.register(TestConfig.class);
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void Origin_헤더가_없는_정적_파일_요청은_통과한다() throws Exception {
        mvc("").perform(get("/assets/test.js")).andExpect(status().isOk());
    }

    @Test
    void 허용_목록에_사이트_도메인이_있으면_Origin_이_붙은_정적_파일_요청도_통과한다() throws Exception {
        mvc(SITE).perform(get("/assets/test.js").header("Origin", SITE).header("Host", "addon-manager.shapinel.com"))
                .andExpect(status().isOk());
    }

    @Test
    void 허용_목록이_비어_있으면_Origin_이_붙은_정적_파일_요청이_차단된다_재현() throws Exception {
        // 운영에서 화면이 비어 보였던 상황: CORS_ALLOWED_ORIGINS 가 비어 있음
        mvc("").perform(get("/assets/test.js").header("Origin", SITE).header("Host", "addon-manager.shapinel.com"))
                .andExpect(status().isForbidden());
    }
}
