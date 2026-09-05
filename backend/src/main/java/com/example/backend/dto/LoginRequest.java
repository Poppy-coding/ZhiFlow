package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    @Size(max = 32, message = "账号不能超过 32 位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 128, message = "密码不能超过 128 位")
    private String password;
}
