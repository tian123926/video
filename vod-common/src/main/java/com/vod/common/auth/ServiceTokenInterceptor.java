package com.vod.common.auth;

import com.vod.common.api.ErrorCode;
import com.vod.common.constant.VideoConstants;
import com.vod.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * 服务间内部接口鉴权（/internal/**）。
 */
@Component
public class ServiceTokenInterceptor implements HandlerInterceptor {

    private final String serviceToken;

    public ServiceTokenInterceptor(@Value("${vod.service-token:vod-internal-token}") String serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader(VideoConstants.HEADER_SERVICE_TOKEN);
        if (!Objects.equals(serviceToken, token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "内部服务令牌无效");
        }
        return true;
    }
}
