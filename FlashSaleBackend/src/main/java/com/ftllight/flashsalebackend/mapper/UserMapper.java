package com.ftllight.flashsalebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ftllight.flashsalebackend.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
