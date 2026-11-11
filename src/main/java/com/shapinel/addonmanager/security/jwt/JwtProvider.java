package com.shapinel.addonmanager.security.jwt;

import com.shapinel.addonmanager.security.principal.CustomUserDetails;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.stream.Collectors;

@Component
public class JwtProvider {

    private final JwtEncoder accessJwtEncoder;
    private final long accessTokenSeconds;

    public JwtProvider(@Qualifier("accessJwtEncoder") JwtEncoder accessJwtEncoder,
                       @Value("${app.jwt.access-valid-seconds}") long accessTokenSeconds) {
        this.accessJwtEncoder = accessJwtEncoder;
        this.accessTokenSeconds = accessTokenSeconds;
    }

    public long getAccessTokenSeconds() {
        return accessTokenSeconds;
    }

    public String issueAccessToken(CustomUserDetails user) {
        Instant now = Instant.now();
        String roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTokenSeconds))
                .claim("typ", "ACCESS")      // 용도 구분 클레임: 다른 용도 토큰의 재사용을 막는다
                .claim("userId", user.getId())
                .claim("roles", roles)
                .build();
        // ✅ alg 를 명시하지 않으면 인코딩에 실패한다
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return accessJwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
