package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 对象签名响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectUrlResponse {

    private String url;

    private OffsetDateTime expiresAt;
}
