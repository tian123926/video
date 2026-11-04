package com.vod.common.auth;

import com.vod.common.api.ErrorCode;
import com.vod.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * 解析 Authorization: Bearer &lt;JWT&gt;，并把 userId 写入 UserContext。
 * 对于 vod.auth.enforce-paths 命中的路径，缺少或无效 token 时返回 401。
 */
@Slf4j
@Component
public class TokenInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> enforcePaths;

    public TokenInterceptor(JwtUtils jwtUtils,
                            @Value("${vod.auth.enforce-paths:}") String enforcePaths) {
        this.jwtUtils = jwtUtils;
        this.enforcePaths = Arrays.stream(enforcePaths.split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Long userId = jwtUtils.parseUserId(header.substring(7).trim());
            if (userId != null) {
                UserContext.set(userId);
            }
        }
        if (UserContext.get() == null && isEnforced(request.getRequestURI())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean isEnforced(String uri) {
        return enforcePaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }
}
