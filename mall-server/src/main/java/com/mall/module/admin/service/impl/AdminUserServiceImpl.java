package com.mall.module.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.enums.ResultStatus;
import com.mall.common.enums.UserStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.admin.entity.dto.AdminUserPageDTO;
import com.mall.module.admin.entity.vo.AdminUserVO;
import com.mall.module.admin.service.AdminUserService;
import com.mall.module.user.entity.po.User;
import com.mall.module.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AdminUserServiceImpl implements AdminUserService {

    private static final Set<Integer> VALID_STATUS = Set.of(0, 1);

    private final UserMapper userMapper;

    public AdminUserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public IPage<AdminUserVO> listUsers(AdminUserPageDTO dto) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt, User::getId);
        if (dto.getKeyword() != null && !dto.getKeyword().isBlank()) {
            wrapper.like(User::getUsername, dto.getKeyword().trim());
        }
        if (dto.getStatus() != null) {
            wrapper.eq(User::getStatus, toUserStatus(dto.getStatus()));
        }

        Page<User> page = userMapper.selectPage(new Page<>(dto.getPageNum(), dto.getPageSize()), wrapper);
        return page.convert(user -> new AdminUserVO()
                .setId(user.getId())
                .setUsername(user.getUsername())
                .setPhone(user.getPhone())
                .setEmail(user.getEmail())
                .setStatus(user.getStatus() == UserStatus.NORMAL ? 1 : 0)
                .setCreatedAt(user.getCreatedAt()));
    }

    @Override
    public void updateStatus(Long userId, Integer status) {
        if (status == null || !VALID_STATUS.contains(status)) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultStatus.ADMIN_USER_NOT_EXIST);
        }
        // 登录流程会检查该字段（UserServiceImpl 抛 USER_BANNED），禁用是真的生效
        user.setStatus(toUserStatus(status));
        userMapper.updateById(user);
    }

    private UserStatus toUserStatus(Integer status) {
        return Integer.valueOf(1).equals(status) ? UserStatus.NORMAL : UserStatus.DISABLED;
    }
}
