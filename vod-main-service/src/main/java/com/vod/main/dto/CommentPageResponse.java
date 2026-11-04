package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评论分页响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentPageResponse {

    private long total;

    private int page;

    private int size;

    private List<CommentItem> items;
}
