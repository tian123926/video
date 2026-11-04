package com.vod.minio.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadInitResp {

    private String uploadId;
    private Long videoId;
    private String bucket;
    private String objectName;
    private Integer chunkSize;
    private Integer totalChunks;
    private OffsetDateTime expireAt;
    private boolean existed;
    private List<PartUrlItem> partUrls;
}
