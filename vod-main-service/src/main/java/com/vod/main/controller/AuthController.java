package com.vod.main.controller;

import com.vod.common.api.ApiResult;
import com.vod.common.api.ErrorCode;
import com.vod.common.auth.JwtUtils;
import com.vod.common.auth.UserContext;
import com.vod.common.constant.VideoConstants;
import com.vod.common.exception.BusinessException;
import com.vod.main.client.MinioInternalClient;
import com.vod.main.dto.LoginRequest;
import com.vod.main.dto.LoginResponse;
import com.vod.main.dto.UserResponse;
import com.vod.main.entity.User;
import com.vod.main.mapper.UserMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录：只接受本系统用户表 UID，不再由前端创建账号或使用用户名登录。
 */
@RestController
@RequestMapping("/api/video/auth")
public class AuthController {

    private static final int AVATAR_EXPIRES_SECONDS = 86400;

    private final UserMapper userMapper;
    private final JwtUtils jwtUtils;
    private final MinioInternalClient minioInternalClient;

    public AuthController(UserMapper userMapper, JwtUtils jwtUtils, MinioInternalClient minioInternalClient) {
        this.userMapper = userMapper;
        this.jwtUtils = jwtUtils;
        this.minioInternalClient = minioInternalClient;
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = resolveUser(request);
        return ApiResult.ok(new LoginResponse(
                jwtUtils.createToken(user.getId()),
                user.getId(),
                user.getUsername(),
                avatarFor(user),
                nullSafe(user.getFollowerCount()),
                nullSafe(user.getLikeCount())));
    }

    @GetMapping("/me")
    public ApiResult<UserResponse> me() {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return ApiResult.ok(new UserResponse(user.getId(), user.getUsername(), avatarFor(user),
                nullSafe(user.getFollowerCount()), nullSafe(user.getLikeCount())));
    }

    private User resolveUser(LoginRequest request) {
        if (request.getUid() == null || request.getUid() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请输入有效的数字 UID");
        }
        User user = userMapper.selectById(request.getUid());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "UID " + request.getUid() + " 不存在");
        }
        return user;
    }

    private String avatarFor(User user) {
        if (user == null) {
            return null;
        }
        if (user.getAvatarObject() == null || user.getAvatarObject().isBlank()) {
            return user.getAvatarUrl();
        }
        try {
            return minioInternalClient
                    .objectUrl(VideoConstants.BUCKET_AVATAR, user.getAvatarObject(), AVATAR_EXPIRES_SECONDS)
                    .getUrl();
        } catch (BusinessException ex) {
            return user.getAvatarUrl();
        }
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
