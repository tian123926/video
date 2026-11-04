package com.vod.minio.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadStatusResp {

    private Long videoId;
    private Integer uploadStatus;
    private Integer videoStatus;
    private Integer progress;
    private String playUrl;
    private String errorMsg;
}
