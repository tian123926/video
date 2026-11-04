package com.vod.minio.config;

import com.vod.common.auth.ServiceTokenInterceptor;
import com.vod.common.auth.TokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TokenInterceptor tokenInterceptor;
    private final ServiceTokenInterceptor serviceTokenInterceptor;

    public WebConfig(TokenInterceptor tokenInterceptor, ServiceTokenInterceptor serviceTokenInterceptor) {
        this.tokenInterceptor = tokenInterceptor;
        this.serviceTokenInterceptor = serviceTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(serviceTokenInterceptor).addPathPatterns("/internal/**");
    }
}
