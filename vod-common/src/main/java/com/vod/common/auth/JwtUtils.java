package com.vod.common.auth;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成与解析（HS256）。
 */
@Component
public class JwtUtils {

    private final byte[] secret;
    private final long expireMinutes;

    public JwtUtils(@Value("${vod.auth.jwt-secret:vod-default-jwt-secret-please-change-123456}") String secret,
                    @Value("${vod.auth.jwt-expire-minutes:10080}") long expireMinutes) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expireMinutes = expireMinutes;
    }

    public String createToken(Long userId) {
        long now = System.currentTimeMillis();
        Date expiresAt = new Date(now + expireMinutes * 60_000L);
        return JWT.create()
                .setPayload("userId", userId)
                .setIssuedAt(new Date(now))
                .setExpiresAt(expiresAt)
                .setKey(secret)
                .sign();
    }

    /**
     * 校验并解析 userId，失败返回 null。
     */
    public Long parseUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            if (!JWTUtil.verify(token, secret)) {
                return null;
            }
            JWT jwt = JWTUtil.parseToken(token);
            JWTValidator.of(jwt).validateDate();
            Object payload = jwt.getPayload("userId");
            if (payload instanceof Number number) {
                return number.longValue();
            }
            return payload == null ? null : Long.parseLong(payload.toString());
        } catch (Exception ex) {
            return null;
        }
    }
}
