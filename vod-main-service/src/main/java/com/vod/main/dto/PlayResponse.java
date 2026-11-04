package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 播放地址响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayResponse {

    private String playType;

    private String m3u8Url;

    private int expiresIn;

    private boolean segmentsSigned;

    private int preloadTime;

    private int minCachedTime;
}
