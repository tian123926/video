package com.vod.main.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表 t_user。
 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String avatarObject;

    private String avatarUrl;

    /** B 站 mid，用于爬虫幂等导入 */
    private String biliMid;

    private Long followerCount;

    private Long likeCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
