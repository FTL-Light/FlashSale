package com.ftllight.flashsalebackend.service;

import com.ftllight.flashsalebackend.dto.LoginDto;

public interface UserService {

    String login(LoginDto loginDto);
}
