package com.vod.main.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 时间转换：数据库 LocalDateTime 统一输出为 +08:00。
 */
public final class TimeUtils {

    public static final ZoneOffset ZONE_OFFSET = ZoneOffset.ofHours(8);

    private TimeUtils() {
    }

    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZONE_OFFSET);
    }

    /**
     * 兼容 minio 微服务返回的 expiresAt：ISO-8601 字符串、本地时间字符串或 epoch 毫秒。
     */
    public static OffsetDateTime parseExpiresAt(Object raw, int fallbackSeconds) {
        if (raw instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue()).atOffset(ZONE_OFFSET);
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return OffsetDateTime.parse(text);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
            try {
                return LocalDateTime.parse(text).atOffset(ZONE_OFFSET);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
            try {
                return Instant.ofEpochMilli(Long.parseLong(text)).atOffset(ZONE_OFFSET);
            } catch (NumberFormatException ignored) {
                // fall through to fallback
            }
        }
        return OffsetDateTime.now(ZONE_OFFSET).plusSeconds(fallbackSeconds);
    }
}
