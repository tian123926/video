package com.vod.minio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_video_transcode_task")
public class TranscodeTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;
    private Long userId;
    private String sourceBucket;
    private String sourceObject;
    private String targetBucket;
    private String targetPrefix;
    private String mqMsgId;
    private Integer status;
    private Integer retryCount;
    private String errorMsg;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
