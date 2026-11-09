package com.vod.main.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户资料 / 当前用户响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long userId;

    private String username;

    private String avatarUrl;

    private Long fansCount;

    private Long likeCount;
}
