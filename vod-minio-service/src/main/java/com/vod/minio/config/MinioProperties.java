package com.vod.minio.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "vod.minio")
public class MinioProperties {

    private String endpoint = "http://127.0.0.1:9000";
    /**
     * 用于生成预签名地址的对外访问入口（如 k8s NodePort）；为空时复用 endpoint。
     */
    private String publicEndpoint = "";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private int presignExpirySeconds = 1800;
}
