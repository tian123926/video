package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评论项（一级评论含前 3 条回复；parentId 分页时作为扁平回复项，replies 为空数组）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentItem {

    private Long commentId;

    private Long userId;

    private String username;

    private String avatarUrl;

    private String content;

    private String imageUrl;

    private Integer likeCount;

    private Integer replyCount;

    private OffsetDateTime createdAt;

    private List<ReplyItem> replies;
}
