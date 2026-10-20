package com.server.zelo.common.auth;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private SecretKey key;

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.access-expiration}")
    private long accessTokenTime;

    @Value("${jwt.refresh-expiration}")
    private long refreshTokenTime;

    private static final String TOKEN_TYPE = "type";
    private static final String ACCESS_TYPE = "access";
    private static final String REFRESH_TYPE = "refresh";
    private static final String BEARER_PREFIX = "Bearer ";

    @PostConstruct
    public void init() {
        byte[] bytes = Base64.getDecoder().decode(secretKey);
        key = Keys.hmacShaKeyFor(bytes);
    }

    public String createAccessToken(Long id) {
        return createToken(id, accessTokenTime, ACCESS_TYPE);
    }

    public String createRefreshToken(Long id) {
        return createToken(id, refreshTokenTime, REFRESH_TYPE);
    }

    // Bearer 접두사는 안 붙이고 순수 토큰만 반환함 (헤더에 담을 때 붙임)
    private String createToken(Long id, long tokenTime, String type) {
        Date date = new Date();

        return Jwts.builder()
                .subject(String.valueOf(id))
                // jti (jwt Id) 설정을 주어서 동시간대 로그인 요청이 들어와도 다른 값의 토큰이 나옴
                .id(UUID.randomUUID().toString())
                // 서명키가 같아서 type이 없으면 리프레시 토큰으로 API를 호출할 수 있음
                .claim(TOKEN_TYPE, type)
                .expiration(new Date(date.getTime() + tokenTime))
                .issuedAt(date)
                .signWith(key)
                .compact();
    }

    public String substringToken(String tokenValue) {
        if (StringUtils.hasText(tokenValue) && tokenValue.startsWith(BEARER_PREFIX)) {
            return tokenValue.substring(7);
        }
        throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        // 헤더안데 토큰이 비어있거나 없음
        if (token == null || token.isEmpty()) {
            return false;
        }
        // 헤더는 있는데 안에 내용이 비어있거나 없음
        if (!StringUtils.hasText(token)) {
            return false;
        }

        try {
            extractClaims(token);
            return true;
        }catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(extractClaims(token).getSubject());
    }

    // 액세스 토큰인지 확인 (리프레시 토큰은 재발급 용도로만 써야 함)
    public boolean isAccessToken(String token) {
        return ACCESS_TYPE.equals(extractClaims(token).get(TOKEN_TYPE, String.class));
    }

    public boolean isRefreshToken(String token) {
        return REFRESH_TYPE.equals(extractClaims(token).get(TOKEN_TYPE, String.class));
    }
}
