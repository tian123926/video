package com.vod.main.client.dto;

import com.vod.main.util.TimeUtils;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * minio 微服务 /internal/object-urls 单项响应数据。
 */
@Data
@NoArgsConstructor
public class ObjectUrlItem {

    private String objectName;

    private String url;

    private Object expiresAt;

    public OffsetDateTime expiresAtOffset(int fallbackSeconds) {
        return TimeUtils.parseExpiresAt(expiresAt, fallbackSeconds);
    }
}
