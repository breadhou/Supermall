package com.mall.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.enums.UserStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.redis.RedisService;
import com.mall.module.user.entity.dto.LoginDTO;
import com.mall.module.user.entity.vo.LoginVO;
import com.mall.module.user.entity.dto.RegisterDTO;
import com.mall.module.user.entity.po.User;
import com.mall.module.user.mapper.UserMapper;
import com.mall.module.user.service.UserService;
import com.mall.security.utils.JwtUtil;
import com.mall.security.utils.UserContext;
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

    @Override
    public LoginVO login(LoginDTO dto) {

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User user = userMapper.selectOne(wrapper);
        if (user == null) {
            throw new BusinessException(ResultStatus.USER_NOT_EXIST);
        }

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultStatus.PASSWORD_ERROR);
        }

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ResultStatus.USER_BANNED);
        }

        String accessToken = JwtUtil.generateAccessToken(user.getId());
        String refreshToken = JwtUtil.generateRefreshToken(user.getId());

        LoginVO vo = new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setRefreshToken(refreshToken);
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        return vo;
    }

    @Override
    public LoginVO refresh() {
        Long userId = UserContext.getUserId();   // 从当前请求的 refresh_token 解析
        String newAccessToken = JwtUtil.generateAccessToken(userId);
        // refresh_token 本身不用换，还是用原来的

        LoginVO vo = new LoginVO();
        vo.setAccessToken(newAccessToken);
        vo.setId(userId);
        return vo;
    }
}
