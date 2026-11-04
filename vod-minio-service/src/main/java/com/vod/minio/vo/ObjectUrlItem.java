package com.vod.minio.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectUrlItem {

    private String objectName;
    private String url;
    private OffsetDateTime expiresAt;
}
