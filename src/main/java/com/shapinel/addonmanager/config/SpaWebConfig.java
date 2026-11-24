package com.shapinel.addonmanager.config;

import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 데스크톱 앱 설치 파일을 두는 서버 폴더. 파일이 크고(100MB 이상) 자주 바뀌어 jar 에 넣지 않고
     * 서버의 폴더를 /downloads/** 로 서빙한다. 비워 두면 다운로드 경로를 열지 않는다.
     */
    @Value("${app.downloads-dir:}")
    private String downloadsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!downloadsDir.isBlank()) {
            // 폴더 밖 경로(../)는 Spring 의 리소스 해석기가 막는다. 이 폴더에는 설치 파일만 둔다.
            registry.addResourceHandler("/downloads/**")
                    .addResourceLocations("file:" + (downloadsDir.endsWith("/") ? downloadsDir : downloadsDir + "/"));
        }
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 없는 API 경로가 200(index.html)로 보이지 않게 404 로 둔다
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws-chat")
                                || resourcePath.startsWith("v3/") || resourcePath.startsWith("swagger-ui")
                                // 설치 파일이 아직 없을 때 index.html(200)로 보이면 화면이 "받을 수 있다"고 오해한다
                                || resourcePath.startsWith("downloads/")) {
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
