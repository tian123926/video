package com.vod.minio.dto;

import lombok.Data;

@Data
public class UploadInitReq {

    private String fileName;
    private Long fileSize;
    private String fileHash;
    private String contentType;
    private Integer chunkSize;
}
