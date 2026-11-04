package com.vod.minio.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteResp {

    private Long videoId;
    private Integer status;
    private Long transcodeTaskId;
    private String mergedObject;
}
