package com.vod.main.dto;

import lombok.Data;

import java.util.List;

/**
 * 爬虫导入请求：一次提交作者、视频与评论，服务端保证幂等。
 */
@Data
public class ImportRequest {

    private Author author;

    private VideoPayload video;

    private List<CommentPayload> comments;

    @Data
    public static class Author {

        private String externalId;

        private String username;

        private String avatarObject;

        private Integer fansCount;

        private Integer likeCount;
    }

    @Data
    public static class VideoPayload {

        private String videoKey;

        private String title;

        private String description;

        private String coverObject;

        /** DASH 清单 objectName，例如 dash/BVxxxx/index.mpd */
        private String manifestObject;

        private Long durationMs;

        /** 分片总数，>0 表示分片已按 video/1.m4s、audio/1.m4s 落盘 */
        private Integer segmentCount;

        /** 单个分片时长（毫秒） */
        private Integer segmentDurationMs;

        private Integer audioSegmentCount;

        private Integer audioSegmentDurationMs;

        private String videoCodecs;

        private String audioCodecs;

        private Integer width;

        private Integer height;

        private Long fileSize;

        /** 播放量（B 站 stat.view） */
        private Long viewCount;

        /** 点赞数（B 站 stat.like） */
        private Long likeCount;

        /** ISO-8601 发布时间 */
        private String publishTime;
    }

    @Data
    public static class CommentPayload {

        private String externalId;

        private String userExternalId;

        private String username;

        private String avatarObject;

        private String content;

        private String imageObject;

        private Integer likeCount;

        private String createdAt;

        private List<CommentPayload> replies;
    }
}
