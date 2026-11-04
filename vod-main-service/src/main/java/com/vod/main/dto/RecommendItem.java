package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 推荐流单条视频。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendItem {

    private Long videoId;

    private Long userId;

    private String username;

    private String title;

    private OffsetDateTime publishTime;

    private Long duration;

    private String firstFrameObject;

    private String firstFrameUrl;

    private OffsetDateTime firstFrameExpiresAt;
}
