package com.vod.main.dto;

import lombok.Data;

import java.util.List;

/**
 * 爬虫统计数据回填请求：只更新播放量 / 点赞数，不触碰视频资源字段与评论。
 */
@Data
public class StatsImportRequest {

    private List<Item> items;

    @Data
    public static class Item {

        private String videoKey;

        private Long viewCount;

        private Long likeCount;
    }
}
