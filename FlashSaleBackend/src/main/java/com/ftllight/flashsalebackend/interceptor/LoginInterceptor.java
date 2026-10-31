package com.ftllight.flashsalebackend.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.ftllight.flashsalebackend.common.CodeMsg;
import com.ftllight.flashsalebackend.common.GlobalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final ThreadLocal<Long> USER_THREAD_LOCAL = new ThreadLocal<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new GlobalException(CodeMsg.UNAUTHORIZED);
        }
        String token = authHeader.substring(7);
        Object loginIdObj = StpUtil.getLoginIdByToken(token);
        if (loginIdObj == null) {
            throw new GlobalException(CodeMsg.UNAUTHORIZED);
        }
        USER_THREAD_LOCAL.set(Long.valueOf(loginIdObj.toString()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        USER_THREAD_LOCAL.remove();
    }

    public static Long getCurrentUserId() {
        return USER_THREAD_LOCAL.get();
    }
}
