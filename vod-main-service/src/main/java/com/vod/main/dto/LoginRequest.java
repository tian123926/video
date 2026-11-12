package com.vod.main.dto;

import lombok.Data;

/**
 * 登录请求：只允许使用本系统用户表主键 UID 登录，不再支持用户名注册/登录。
 */
@Data
public class LoginRequest {

    /** 用户表主键，登录界面输入的数字 UID */
    private Long uid;
}
