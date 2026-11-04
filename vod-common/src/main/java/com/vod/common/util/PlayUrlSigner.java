package com.vod.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 播放地址签名：expires + HMAC-SHA256(videoId:expires)。
 */
@Component
public class PlayUrlSigner {

    private final String secret;

    public PlayUrlSigner(@Value("${vod.play.sign-secret:vod-play-sign-secret-please-change}") String secret) {
        this.secret = secret;
    }

    public String sign(long videoId, long expiresAtMillis) {
        return hmac(videoId + ":" + expiresAtMillis);
    }

    public boolean verify(long videoId, long expiresAtMillis, String sign) {
        if (sign == null || System.currentTimeMillis() > expiresAtMillis) {
            return false;
        }
        return MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8),
                sign(videoId, expiresAtMillis).getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC 计算失败", ex);
        }
    }
}
