package com.mall.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.redis.RedisService;
import com.mall.module.user.entity.LoginVO;
import com.mall.module.user.entity.RegisterDTO;
import com.mall.module.user.entity.User;
import com.mall.module.user.mapper.UserMapper;
import com.mall.module.user.service.UserService;
import com.mall.security.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserServiceImpl implements UserService {

    @Autowired
    UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    RedisService redisService;

    @Override
    public LoginVO register(RegisterDTO dto) {

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultStatus.DATA_ALREADY_EXIST);
        }

        wrapper.clear();
        wrapper.eq(User::getPhone, dto.getPhone());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ResultStatus.MOBILE_ERROR);
        }

       User user = new User()
               .setUsername(dto.getUsername())
               .setPassword(passwordEncoder.encode(dto.getPassword()))
               .setPhone(dto.getPhone())
               .setId(SnowflakeIdUtil.nextId());

       userMapper.insert(user);

       String accessToken = JwtUtil.generateAccessToken(user.getId());
       String refreshToken = JwtUtil.generateRefreshToken(user.getId());

       LoginVO vo = new LoginVO();
       vo.setAccessToken(accessToken);
       vo.setRefreshToken(refreshToken);
       vo.setId(user.getId());
       vo.setUsername(user.getUsername());
       return vo;

    }
}
