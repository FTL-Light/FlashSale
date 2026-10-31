package com.ftllight.flashsalebackend.controller;

import com.ftllight.flashsalebackend.common.Result;
import com.ftllight.flashsalebackend.dto.LoginDto;
import com.ftllight.flashsalebackend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginDto loginDto) {
        return Result.success(userService.login(loginDto));
    }
}
