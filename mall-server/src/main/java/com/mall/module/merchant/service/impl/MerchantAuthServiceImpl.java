package com.mall.module.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.merchant.entity.dto.MerchantLoginDTO;
import com.mall.module.merchant.entity.po.AdminUser;
import com.mall.module.merchant.entity.po.Merchant;
import com.mall.module.merchant.entity.vo.MerchantLoginVO;
import com.mall.module.merchant.mapper.AdminUserMapper;
import com.mall.module.merchant.mapper.MerchantMapper;
import com.mall.module.merchant.service.MerchantAuthService;
import com.mall.security.utils.MerchantJwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class MerchantAuthServiceImpl implements MerchantAuthService {

    private static final int MERCHANT_ACTIVE = 1;

    private final AdminUserMapper adminUserMapper;
    private final MerchantMapper merchantMapper;
    private final PasswordEncoder passwordEncoder;
    private final MerchantJwtUtil merchantJwtUtil;

    public MerchantAuthServiceImpl(AdminUserMapper adminUserMapper,
                                   MerchantMapper merchantMapper,
                                   PasswordEncoder passwordEncoder,
                                   MerchantJwtUtil merchantJwtUtil) {
        this.adminUserMapper = adminUserMapper;
        this.merchantMapper = merchantMapper;
        this.passwordEncoder = passwordEncoder;
        this.merchantJwtUtil = merchantJwtUtil;
    }

    @Override
    public MerchantLoginVO login(MerchantLoginDTO dto) {
        AdminUser admin = adminUserMapper.selectOne(
                new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, dto.getUsername()));

        // 账号不存在与密码错误返回同一个码值，不泄漏用户名是否存在
        if (admin == null || !passwordEncoder.matches(dto.getPassword(), admin.getPassword())) {
            throw new BusinessException(ResultStatus.MERCHANT_LOGIN_FAILED);
        }
        if (admin.getMerchantId() == null) {
            throw new BusinessException(ResultStatus.MERCHANT_NOT_BOUND);
        }

        Merchant merchant = merchantMapper.selectById(admin.getMerchantId());
        if (merchant == null || !Integer.valueOf(MERCHANT_ACTIVE).equals(merchant.getStatus())) {
            throw new BusinessException(ResultStatus.MERCHANT_DISABLED);
        }

        String token = merchantJwtUtil.generateToken(admin.getId(), merchant.getId(), admin.getRole());
        return new MerchantLoginVO()
                .setAccessToken(token)
                .setAdminUserId(admin.getId())
                .setMerchantId(merchant.getId())
                .setMerchantName(merchant.getName())
                .setRole(admin.getRole());
    }
}
