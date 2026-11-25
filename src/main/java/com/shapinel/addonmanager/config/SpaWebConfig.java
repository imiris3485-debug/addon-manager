package com.shapinel.addonmanager.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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
        // 빌드 결과(/assets/**)는 파일 이름에 내용 해시가 붙어 있어 내용이 바뀌면 이름도 바뀐다 → 오래 캐시해도 안전하다.
        // 이 핸들러가 먼저 와야 아래 SPA 대체(index.html) 해석기가 없는 assets 파일을 200 으로 가리지 않는다.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                // index.html 은 이름이 늘 같다. 캐시 지시가 없으면 브라우저·앱이 마지막 수정 시각으로 짐작해 옛 index.html 을
                // 한동안 재사용해서, 서버를 다시 배포해도 옛 화면이 뜬다. 매번 서버에 확인(304)하게 한다.
                .setCacheControl(CacheControl.noCache())
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
