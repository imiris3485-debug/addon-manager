package com.shapinel.addonmanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 프론트(React SPA) 정적 파일 서빙. 파일이 없는 경로(예: /installed 새로고침)는 index.html 로 돌려
 * 클라이언트 라우터가 처리하게 한다. API/WebSocket 경로는 index.html 로 가리지 않는다.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 없는 API 경로가 200(index.html)로 보이지 않게 404 로 둔다
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws-chat")
                                || resourcePath.startsWith("v3/") || resourcePath.startsWith("swagger-ui")) {
                            return null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
