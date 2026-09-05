package com.example.backend.config;

import com.example.backend.common.ErrorCode;
import com.example.backend.common.Result;
import com.example.backend.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Resource
    private AuthService authService;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            Long userId = authService.resolveUser(request.getHeader("Authorization"));
            request.setAttribute(AuthService.REQUEST_USER_ID, userId);
            return true;
        } catch (SecurityException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
            return false;
        }
    }
}
