package com.example.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.UserInfo;
import com.example.backend.entity.User;
import com.example.backend.mapper.UserMapper;
import com.example.backend.service.AuthService;
import com.example.backend.utils.JwtUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final String PASSWORD_PREFIX = "pbkdf2";
    private static final int PASSWORD_ITERATIONS = 210_000;//数据加密迭代次数
    private static final int PASSWORD_KEY_BITS = 256;//生成哈希长度
    private static final int SALT_BYTES = 16;
    private static final String LOGIN_FAILURE_PREFIX = "auth:login-failures:";
    private static final int MAX_LOGIN_FAILURES = 8;//最大登录重复次数
    private static final long LOGIN_FAILURE_WINDOW_MINUTES = 10;



    @Resource
    private StringRedisTemplate redisTemplate;

    @Resource
    private UserMapper userMapper;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expire-hours}")
    private long jwtExpireHours;

    private final SecureRandom secureRandom = new SecureRandom();//生成强安全性随机数

    /**
     * 注册用户
     * @param request
     * @return
     */
    @Override
    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.getUsername());
        String password = request.getPassword();
        if (username == null || password == null || password.length() < 8
                || password.length() > 128) {
            return response(400, "账号需为 3-32 位字母、数字或下划线，密码需为 8-128 位", null, null);
        }

        String nickname = normalizeNickname(request.getNickname());
        if (nickname == null) {
            return response(400, "昵称不能超过 50 个字符", null, null);
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        if (userMapper.selectCount(query) > 0) {
            return response(409, "该账号已存在", null, null);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(hashPassword(password));
        user.setNickname(nickname.isBlank() ? "用户" + System.currentTimeMillis() : nickname);
        user.setRole("USER");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException error) {
            return response(409, "该账号已存在", null, null);
        }
        log.info("user_registered userId={} username={}", user.getId(), username);
        return response(200, "注册成功", new UserInfo(
                user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole()), null);
    }

    /**
     * 登录接口
     * @param request
     * @return
     */
    @Override
    public AuthResponse login(LoginRequest request) {
        String username = normalizeUsername(request.getUsername());
        if (username == null || request.getPassword() == null || request.getPassword().isBlank()) {
            return response(400, "请输入账号和密码", null, null);
        }
        if (!loginAttemptAllowed(username)) {
            return response(429, "登录尝试过于频繁，请稍后再试", null, null);
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        User user = userMapper.selectOne(query);
        if (user == null || !passwordMatches(request.getPassword(), user.getPassword())) {
            recordLoginFailure(username);
            return response(401, "账号或密码错误", null, null);
        }

        if (!isHashed(user.getPassword())) {
            user.setPassword(hashPassword(request.getPassword()));
            userMapper.updateById(user);
        }
        clearLoginFailures(username);
        String token = JwtUtils.generateToken(user.getId(), jwtSecret, jwtExpireHours);
        log.info("user_logged_in userId={}", user.getId());
        return response(200, "登录成功", new UserInfo(
                user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole()), token);
    }

    @Override
    public void requireAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new SecurityException("仅管理员可操作失败任务");
        }
    }

    /**
     * 密码加密存放
     * @param password
     * @return
     */
    private String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);//生成 16 字节随机盐
        byte[] hash = derive(password.toCharArray(), salt, PASSWORD_ITERATIONS);//用户密码 + 盐做 PBKDF2 计算
        return String.join("$",
                PASSWORD_PREFIX,
                String.valueOf(PASSWORD_ITERATIONS),
                Base64.getEncoder().withoutPadding().encodeToString(salt),
                Base64.getEncoder().withoutPadding().encodeToString(hash));
    }

    /**
     * 判断密码是否正确
     * @param rawPassword
     * @param storedPassword
     * @return
     */
    private boolean passwordMatches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) {
            return false;
        }
        if (!isHashed(storedPassword)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    storedPassword.getBytes(StandardCharsets.UTF_8));
        }

        try {
            String[] parts = storedPassword.split("\\$", -1);
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isHashed(String password) {
        return password != null && password.startsWith(PASSWORD_PREFIX + "$");
    }

    @Override
    public Long resolveUser(String authorization) {
        String token = bearerToken(authorization);
        return JwtUtils.parseUserId(token, jwtSecret);
    }

    /**
     * 登出接口
     * @param authorization
     */
    @Override
    public void revokeSession(String authorization) {
        bearerToken(authorization);
    }

    private boolean loginAttemptAllowed(String username) {
        String value = redisTemplate.opsForValue().get(LOGIN_FAILURE_PREFIX + username);
        if (value == null) {
            return true;
        }
        try {
            return Long.parseLong(value) < MAX_LOGIN_FAILURES;
        } catch (NumberFormatException e) {
            redisTemplate.delete(LOGIN_FAILURE_PREFIX + username);
            return true;
        }
    }

    /**
     * 记录失败次数
     * redis sei key value expire + 10分钟
     * 十分钟不能重复登录失败8次
     * @param username
     */
    private void recordLoginFailure(String username) {
        String key = LOGIN_FAILURE_PREFIX + username;
        Long failures = redisTemplate.opsForValue().increment(key);
        if (failures != null && failures == 1) {
            redisTemplate.expire(key, LOGIN_FAILURE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
    }

    private void clearLoginFailures(String username) {
        redisTemplate.delete(LOGIN_FAILURE_PREFIX + username);
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String normalized = username.trim();
        return normalized.matches("[A-Za-z0-9_]{3,32}") ? normalized : null;
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "";
        }
        String normalized = nickname.trim();
        return normalized.length() <= 50 ? normalized : null;
    }



    private AuthResponse response(int code, String message, UserInfo userInfo, String token) {
        return new AuthResponse(code, message, userInfo, token);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SecurityException("请先登录");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            throw new SecurityException("无效的登录凭证");
        }
        return token;
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        } finally {
            spec.clearPassword();
        }
    }
}
