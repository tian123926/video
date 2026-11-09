package com.vod.main.dto;

import lombok.Data;

import java.util.List;

/**
 * 仅回填评论（视频已存在时使用）。
 */
@Data
public class CommentImportRequest {

    private String videoKey;

    private List<ImportRequest.CommentPayload> comments;
}
