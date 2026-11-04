package com.vod.transcode.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * minio 服务 /internal/object-url 的 data 结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectUrlResp {

    private String url;
    private String expiresAt;
}
