package com.example.backend.controller;

import com.example.backend.common.ErrorCode;
import com.example.backend.common.Result;
import com.example.backend.dto.AuthData;
import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.exception.BusinessException;
import com.example.backend.service.AuthService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private AuthService authService;

    /**
     * 注册接口
     * @param request
     * @return
     */
    @PostMapping("/register")
    public Result<AuthData> register(@Valid @RequestBody RegisterRequest request) {
        return toResult(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthData> login(@Valid @RequestBody LoginRequest request) {
        return toResult(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authorization) {
        authService.revokeSession(authorization);
        return Result.ok();
    }

    private Result<AuthData> toResult(AuthResponse response) {
        if (response.getCode() != 200) {
            throw new BusinessException(mapAuthCode(response.getCode()), response.getMsg());
        }
        return Result.ok(new AuthData(response.getUserInfo(), response.getToken()));
    }

    private ErrorCode mapAuthCode(int code) {
        return switch (code) {
            case 400 -> ErrorCode.INVALID_ARGUMENT;
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 409 -> ErrorCode.CONFLICT;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
