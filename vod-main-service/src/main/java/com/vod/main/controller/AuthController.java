package com.vod.main.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.api.ApiResult;
import com.vod.common.api.ErrorCode;
import com.vod.common.auth.JwtUtils;
import com.vod.common.auth.UserContext;
import com.vod.common.exception.BusinessException;
import com.vod.main.dto.LoginRequest;
import com.vod.main.dto.LoginResponse;
import com.vod.main.dto.UserResponse;
import com.vod.main.entity.User;
import com.vod.main.mapper.UserMapper;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录：前端无需注册，用户名不存在时自动创建。
 */
@RestController
@RequestMapping("/api/video/auth")
public class AuthController {

    private final UserMapper userMapper;
    private final JwtUtils jwtUtils;

    public AuthController(UserMapper userMapper, JwtUtils jwtUtils) {
        this.userMapper = userMapper;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        if (username.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "username 不能为空");
        }
        User user = findByUsername(username);
        if (user == null) {
            User created = new User();
            created.setUsername(username);
            try {
                userMapper.insert(created);
                user = created;
            } catch (DataIntegrityViolationException ex) {
                user = findByUsername(username);
            }
        }
        if (user == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return ApiResult.ok(new LoginResponse(jwtUtils.createToken(user.getId()), user.getId(),
                user.getUsername(), user.getAvatarUrl()));
    }

    @GetMapping("/me")
    public ApiResult<UserResponse> me() {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return ApiResult.ok(new UserResponse(user.getId(), user.getUsername(), user.getAvatarUrl()));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
    }
}
