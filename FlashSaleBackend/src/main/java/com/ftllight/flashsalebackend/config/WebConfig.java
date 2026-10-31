package com.ftllight.flashsalebackend.config;

import com.ftllight.flashsalebackend.interceptor.LoginInterceptor;
import com.ftllight.flashsalebackend.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/seckill/**", "/api/order/**")
                .excludePathPatterns("/api/user/login", "/api/goods/**");

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/seckill/**")
                .excludePathPatterns("/api/user/login", "/api/goods/**");
    }
}
