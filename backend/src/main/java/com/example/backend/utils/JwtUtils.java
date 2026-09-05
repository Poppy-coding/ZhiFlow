package com.example.backend.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public final class JwtUtils {

    private JwtUtils() {
    }

    public static String generateToken(Long userId, String secret, long expireHours) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + expireHours * 60 * 60 * 1000);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiresAt)
                .signWith(secretKey(secret))
                .compact();
    }

    public static Long parseUserId(String token, String secret) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey(secret))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new SecurityException("登录状态已失效", e);
        }
    }

    private static SecretKey secretKey(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwt.secret must be at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
