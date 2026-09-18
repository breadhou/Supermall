package com.mall.module.merchant.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.merchant.entity.dto.MerchantLoginDTO;
import com.mall.module.merchant.entity.po.AdminUser;
import com.mall.module.merchant.entity.po.Merchant;
import com.mall.module.merchant.mapper.AdminUserMapper;
import com.mall.module.merchant.mapper.MerchantMapper;
import com.mall.security.utils.MerchantJwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantAuthServiceImplTest {

    private static final Long MERCHANT_ID = 5001L;

    @Mock
    private AdminUserMapper adminUserMapper;
    @Mock
    private MerchantMapper merchantMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private MerchantJwtUtil merchantJwtUtil;

    private MerchantAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MerchantAuthServiceImpl(
                adminUserMapper, merchantMapper, passwordEncoder, merchantJwtUtil);
    }

    private MerchantLoginDTO dto() {
        MerchantLoginDTO dto = new MerchantLoginDTO();
        dto.setUsername("shop_a");
        dto.setPassword("secret");
        return dto;
    }

    @Test
    void login_shouldReturnSameCodeForUnknownAccountAndWrongPassword() {
        when(adminUserMapper.selectOne(any())).thenReturn(null);
        BusinessException unknown = assertThrows(
                BusinessException.class, () -> service.login(dto()));

        when(adminUserMapper.selectOne(any()))
                .thenReturn(new AdminUser().setId(1L).setPassword("hash").setMerchantId(MERCHANT_ID));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        BusinessException wrongPassword = assertThrows(
                BusinessException.class, () -> service.login(dto()));

        // 两种失败必须不可区分，否则可以枚举用户名
        assertEquals(ResultStatus.MERCHANT_LOGIN_FAILED, unknown.getStatus());
        assertEquals(unknown.getStatus(), wrongPassword.getStatus());
    }

    @Test
    void login_shouldRejectAccountWithoutMerchantBinding() {
        when(adminUserMapper.selectOne(any()))
                .thenReturn(new AdminUser().setId(1L).setPassword("hash").setMerchantId(null));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.login(dto()));

        assertEquals(ResultStatus.MERCHANT_NOT_BOUND, exception.getStatus());
        verify(merchantJwtUtil, never()).generateToken(any(), any(), anyString());
    }

    @Test
    void login_shouldRejectDisabledMerchant() {
        when(adminUserMapper.selectOne(any()))
                .thenReturn(new AdminUser().setId(1L).setPassword("hash").setMerchantId(MERCHANT_ID));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(merchantMapper.selectById(MERCHANT_ID))
                .thenReturn(new Merchant().setId(MERCHANT_ID).setStatus(0));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.login(dto()));

        assertEquals(ResultStatus.MERCHANT_DISABLED, exception.getStatus());
        verify(merchantJwtUtil, never()).generateToken(any(), any(), anyString());
    }

    @Test
    void login_shouldIssueTokenBoundToMerchant() {
        when(adminUserMapper.selectOne(any()))
                .thenReturn(new AdminUser().setId(1L).setPassword("hash")
                        .setMerchantId(MERCHANT_ID).setRole("ADMIN"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(merchantMapper.selectById(MERCHANT_ID))
                .thenReturn(new Merchant().setId(MERCHANT_ID).setName("店铺A").setStatus(1));
        when(merchantJwtUtil.generateToken(1L, MERCHANT_ID, "ADMIN")).thenReturn("token");

        assertEquals("token", service.login(dto()).getAccessToken());
    }
}
