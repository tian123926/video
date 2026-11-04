package com.vod.minio.dto;

import lombok.Data;

import java.util.List;

@Data
public class PartUrlReq {

    private String uploadId;
    private List<Integer> partNumbers;
}
