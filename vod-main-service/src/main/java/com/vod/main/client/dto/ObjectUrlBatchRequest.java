package com.vod.main.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * minio 微服务 /internal/object-urls 请求体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectUrlBatchRequest {

    private String bucket;

    private List<String> objectNames;

    private int expires;
}
