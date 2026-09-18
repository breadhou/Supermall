package com.mall.module.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.admin.entity.dto.AdminLoginDTO;
import com.mall.module.admin.entity.vo.AdminLoginVO;
import com.mall.module.admin.service.AdminAuthService;
import com.mall.module.merchant.entity.po.AdminUser;
import com.mall.module.merchant.mapper.AdminUserMapper;
import com.mall.security.utils.MerchantJwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final MerchantJwtUtil merchantJwtUtil;

    public AdminAuthServiceImpl(AdminUserMapper adminUserMapper,
                                PasswordEncoder passwordEncoder,
                                MerchantJwtUtil merchantJwtUtil) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.merchantJwtUtil = merchantJwtUtil;
    }

    @Override
    public AdminLoginVO login(AdminLoginDTO dto) {
        AdminUser admin = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, dto.getUsername()));

        if (admin == null || !passwordEncoder.matches(dto.getPassword(), admin.getPassword())) {
            throw new BusinessException(ResultStatus.ADMIN_LOGIN_FAILED);
        }
        // 走到这里说明密码已正确，此时透露「你不是管理员」不泄漏账号是否存在
        if (!SUPER_ADMIN.equals(admin.getRole())) {
            throw new BusinessException(ResultStatus.ADMIN_NOT_SUPER);
        }

        // 平台管理员不属于任何商家，merchantId 传 null
        String token = merchantJwtUtil.generateToken(admin.getId(), null, admin.getRole());
        return new AdminLoginVO()
                .setAccessToken(token)
                .setAdminUserId(admin.getId())
                .setUsername(admin.getUsername())
                .setRole(admin.getRole());
    }
}
