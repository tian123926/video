package com.vod.minio.dto;

import lombok.Data;

import java.util.List;

@Data
public class ObjectUrlsReq {

    private String bucket;
    private List<String> objectNames;
    private Integer expires;
}
