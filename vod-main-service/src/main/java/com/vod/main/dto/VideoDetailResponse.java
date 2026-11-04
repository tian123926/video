package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 视频详情响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoDetailResponse {

    private Long videoId;

    private Long userId;

    private String username;

    private String avatarUrl;

    private String title;

    private String description;

    private OffsetDateTime publishTime;

    private Long duration;

    private Integer status;

    private String firstFrameUrl;

    private Long commentCount;
}
