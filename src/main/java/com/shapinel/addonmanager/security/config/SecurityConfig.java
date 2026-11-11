package com.shapinel.addonmanager.security.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 공개 경로는 한 곳에서 관리한다. 추가 시 반드시 이유를 주석으로 남긴다. */
    private static final String[] PUBLIC_POST = {
            "/api/v1/auth/login",       // 로그인은 토큰 발급 전이므로 공개
            "/api/v1/auth/signup",      // 가입 요청은 계정이 없는 상태이므로 공개(승인 전에는 로그인 불가)
    };
    private static final String[] SWAGGER = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
    };
    /** WebSocket 핸드셰이크. 인증은 STOMP CONNECT 시 인터셉터에서 토큰으로 검증한다. */
    private static final String[] WEBSOCKET = {
            "/ws-chat/**",
    };

    @Bean
    @Order(0)
    public SecurityFilterChain websocketChain(HttpSecurity http) throws Exception {
        http.securityMatcher(WEBSOCKET)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http,
                                        @Qualifier("accessJwtDecoder") JwtDecoder accessJwtDecoder) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)          // 쿠키 인증을 쓰지 않으므로 CSRF 불필요
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())                // CorsConfigurationSource 빈 사용
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j
                        .decoder(accessJwtDecoder)
                        .jwtAuthenticationConverter(accessAuthConverter())));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain docsChain(HttpSecurity http) throws Exception {
        // 운영 프로필에서는 springdoc 자체를 꺼서 이 경로가 아무것도 노출하지 않는다
        http.securityMatcher(SWAGGER)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(99)
    public SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        // 위 체인(/api, WebSocket, Swagger)에 매칭되지 않은 나머지는 프론트 정적 파일(SPA)이다.
        // 읽기(GET/HEAD)만 공개하고 그 외 메서드는 기본 거부한다.
        http.authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    // ---------- JWT (HS256) ----------
    @Bean("accessJwtDecoder")
    public JwtDecoder accessJwtDecoder(@Value("${app.jwt.access-secret}") String secret) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(hmacKey(secret)).build();
        // typ=ACCESS 인 토큰만 통과시킨다 (다른 용도 토큰 재사용 차단)
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<String>("typ", "ACCESS"::equals)));
        return decoder;
    }

    @Bean("accessJwtEncoder")
    public JwtEncoder accessJwtEncoder(@Value("${app.jwt.access-secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey(secret)));
    }

    private static SecretKey hmacKey(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // 짧은 키는 HS256 을 약화시키므로 기동 단계에서 막는다
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT secret은 32바이트 이상이어야 합니다(HS256)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtAuthenticationConverter accessAuthConverter() {
        JwtGrantedAuthoritiesConverter gac = new JwtGrantedAuthoritiesConverter();
        gac.setAuthoritiesClaimName("roles");
        gac.setAuthorityPrefix("");
        gac.setAuthoritiesClaimDelimiter(",");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(gac);
        return c;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);                   // 와일드카드(*) 금지 — 프로필별 도메인 목록
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
