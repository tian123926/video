package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 推荐流分页响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendResponse {

    private List<RecommendItem> items;

    private String nextCursor;

    private boolean hasMore;
}
