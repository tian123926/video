package com.vod.main.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频主表 t_video。
 */
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

    /** 分片总数（DASH 分片模式，>0 表示使用动态生成索引） */
    private Integer segmentCount;

    /** 单个分片时长（毫秒） */
    private Integer segmentDurationMs;

    /** 音频分片总数 */
    private Integer audioSegmentCount;

    /** 音频单分片时长（毫秒） */
    private Integer audioSegmentDurationMs;

    /** 视频编码，如 avc1.64001F */
    private String videoCodecs;

    /** 音频编码，如 mp4a.40.2 */
    private String audioCodecs;

    private Integer width;

    private Integer height;

    /** 播放量，来自 B 站 stat.view（或站内播放上报） */
    private Long viewCount;

    /** 评论数（含回复），导入评论时刷新 */
    private Long commentCount;

    /** 点赞数，来自 B 站 stat.like */
    private Long likeCount;

    private String fileHash;

    private Long fileSize;

    private Integer status;

    private String errorMsg;

    private LocalDateTime publishTime;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
