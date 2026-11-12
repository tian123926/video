package com.vod.main.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论 / 回复请求。
 */
@Data
public class CommentCreateRequest {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过 1000 字")
    private String content;

    /** 0 或空表示一级评论；大于 0 表示回复该评论 */
    private Long parentId;
}
