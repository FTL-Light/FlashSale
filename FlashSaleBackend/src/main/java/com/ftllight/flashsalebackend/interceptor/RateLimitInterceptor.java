package com.ftllight.flashsalebackend.interceptor;

import com.ftllight.flashsalebackend.common.CodeMsg;
import com.ftllight.flashsalebackend.common.GlobalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int MAX_COUNT = 5;
    private static final int WINDOW_SIZE = 5;
    private static final String REDIS_KEY_PREFIX = "ratelimit:seckill:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        Long currentUserId = LoginInterceptor.getCurrentUserId();
        if (currentUserId == null) return true;

        String redisKey = REDIS_KEY_PREFIX + currentUserId;
        Long count = stringRedisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(redisKey, WINDOW_SIZE, TimeUnit.SECONDS);
        }
        if (count != null && count > MAX_COUNT) {
            throw new GlobalException(CodeMsg.RATE_LIMIT);
        }
        return true;
    }
}
