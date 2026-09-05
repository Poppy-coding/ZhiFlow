package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "账号不能为空")
    @Size(min = 3, max = 32, message = "账号需为 3-32 位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 128, message = "密码需为 8-128 位")
    private String password;

    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;
}
