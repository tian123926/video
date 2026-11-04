package com.vod.minio.dto;

import lombok.Data;

@Data
public class PartItem {

    private Integer partNumber;
    private String etag;
}
