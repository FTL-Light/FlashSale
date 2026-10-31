package com.ftllight.flashsalebackend.service.Impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ftllight.flashsalebackend.common.CodeMsg;
import com.ftllight.flashsalebackend.common.GlobalException;
import com.ftllight.flashsalebackend.dto.LoginDto;
import com.ftllight.flashsalebackend.entity.User;
import com.ftllight.flashsalebackend.mapper.UserMapper;
import com.ftllight.flashsalebackend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public String login(LoginDto loginDto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, loginDto.getPhone()));
        if (user == null) throw new GlobalException(CodeMsg.USER_NOT_FOUND);

        String raw = user.getSalt() + loginDto.getPassword();
        String inputHash = DigestUtils.md5DigestAsHex(
                raw.getBytes(StandardCharsets.UTF_8));
        if (!inputHash.equals(user.getPassword()))
            throw new GlobalException(CodeMsg.PASSWORD_ERROR);

        StpUtil.login(user.getId());
        return StpUtil.getTokenValue();
    }
}
