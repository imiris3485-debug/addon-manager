package com.shapinel.addonmanager.config.websocket;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * STOMP CONNECT 프레임의 Authorization 헤더(Bearer JWT)를 검증한다.
 * 핸드셰이크 경로는 permitAll 이므로 실제 인증은 여기서만 이루어진다.
 * 순환 참조를 피하기 위해 SimpMessagingTemplate 을 쓰는 서비스는 주입하지 않는다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder accessJwtDecoder;

    public StompAuthChannelInterceptor(@Qualifier("accessJwtDecoder") JwtDecoder accessJwtDecoder) {
        this.accessJwtDecoder = accessJwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("인증 토큰이 없습니다.");
        }
        try {
            Jwt jwt = accessJwtDecoder.decode(header.substring(7));
            String roles = jwt.getClaimAsString("roles");
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    jwt.getSubject(), null, AuthorityUtils.commaSeparatedStringToAuthorityList(roles)));
            // 이후 SEND 에서 발신자 정보는 클라이언트 값이 아니라 이 세션 값으로 덮어써야 한다(위조 방지)
            accessor.getSessionAttributes().put("USER_ID", jwt.getClaim("userId"));
        } catch (JwtException e) {
            throw new MessagingException("유효하지 않은 토큰입니다.");
        }
        return message;
    }
}
