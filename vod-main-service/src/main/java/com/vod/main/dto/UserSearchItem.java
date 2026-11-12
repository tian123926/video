package com.vod.main.dto;

/**
 * 搜索「用户」页签条目。
 */
public record UserSearchItem(Long userId,
                             String username,
                             String avatarUrl,
                             Long fansCount,
                             Long likeCount,
                             Long videoCount) {
}
