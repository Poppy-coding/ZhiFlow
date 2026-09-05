package com.example.backend.service;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;

public interface AuthService {

    String REQUEST_USER_ID = "authenticatedUserId";

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    void requireAdmin(Long userId);

    Long resolveUser(String authorization);

    void revokeSession(String authorization);
}
