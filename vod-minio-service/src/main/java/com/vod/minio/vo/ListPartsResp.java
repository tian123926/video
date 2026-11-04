package com.vod.minio.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListPartsResp {

    private String uploadId;
    private Integer totalChunks;
    private List<UploadedPart> uploadedParts;
    private List<Integer> missingParts;
}
