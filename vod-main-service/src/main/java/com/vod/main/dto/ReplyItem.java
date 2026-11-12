package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 二级回复。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplyItem {

    private Long commentId;

    private Long userId;

    private String username;

    private String avatarUrl;

    private String content;

    private String imageUrl;

    private OffsetDateTime createdAt;
}
