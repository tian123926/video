package com.vod.minio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_video_upload_session")
public class UploadSession {

    @TableId(value = "upload_id", type = IdType.INPUT)
    private String uploadId;

    private Long userId;
    private Long videoId;
    private String bucket;
    private String objectName;
    private String fileName;
    private Long fileSize;
    private String fileHash;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer status;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
