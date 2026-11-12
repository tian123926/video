package com.vod.main.dto;

import java.util.List;

/**
 * 视频检索结果（ES 命中，数据库兜底）。
 */
public record SearchResponse(String keyword,
                             long total,
                             int page,
                             int size,
                             boolean hasMore,
                             List<RecommendItem> items,
                             String nextCursor,
                             String source) {
}
