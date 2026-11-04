package com.vod.minio.dto;

import lombok.Data;

import java.util.List;

@Data
public class CompleteReq {

    private String uploadId;
    private List<PartItem> parts;
}
