package com.vod.minio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_video")
public class Video {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    private String description;
    private String sourceBucket;
    private String sourceObject;
    private String coverBucket;
    private String firstFrameObject;
    private String hlsBucket;
    private String m3u8Object;
    private Integer durationMs;
    private String fileHash;
    private Long fileSize;
    private Integer status;
    private String errorMsg;
    private LocalDateTime publishTime;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
