package com.mall.module.admin.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.admin.entity.dto.AdminLoginDTO;
import com.mall.module.admin.entity.vo.AdminLoginVO;
import com.mall.module.merchant.entity.po.AdminUser;
import com.mall.module.merchant.mapper.AdminUserMapper;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceImplTest {

    @Mock
    private AdminUserMapper adminUserMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private MerchantJwtUtil merchantJwtUtil;

    private AdminAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminAuthServiceImpl(adminUserMapper, passwordEncoder, merchantJwtUtil);
    }

    private AdminLoginDTO dto() {
        AdminLoginDTO dto = new AdminLoginDTO();
        dto.setUsername("ops");
        dto.setPassword("secret");
        return dto;
    }

    @Test
    void login_shouldReturnSameCodeForUnknownAccountAndWrongPassword() {
        when(adminUserMapper.selectOne(any())).thenReturn(null);
        BusinessException unknown = assertThrows(
                BusinessException.class, () -> service.login(dto()));

        when(adminUserMapper.selectOne(any()))
                .thenReturn(new AdminUser().setId(1L).setPassword("hash").setRole("SUPER_ADMIN"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        BusinessException wrongPassword = assertThrows(
                BusinessException.class, () -> service.login(dto()));

        assertEquals(ResultStatus.ADMIN_LOGIN_FAILED, unknown.getStatus());
        assertEquals(unknown.getStatus(), wrongPassword.getStatus());
    }

    @Test
    void login_shouldRejectMerchantAccountEvenWithCorrectPassword() {
        when(adminUserMapper.selectOne(any()))
                .thenReturn(new AdminUser().setId(1L).setPassword("hash")
                        .setRole("ADMIN").setMerchantId(5001L));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.login(dto()));

        assertEquals(ResultStatus.ADMIN_NOT_SUPER, exception.getStatus());
        verify(merchantJwtUtil, never()).generateToken(any(), any(), anyString());
    }

    @Test
    void login_shouldIssueTokenWithoutMerchantBinding() {
        when(adminUserMapper.selectOne(any()))
                .thenReturn(new AdminUser().setId(1L).setUsername("ops")
                        .setPassword("hash").setRole("SUPER_ADMIN"));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        // 平台管理员不属于任何商家，merchantId 必须是 null
        when(merchantJwtUtil.generateToken(1L, null, "SUPER_ADMIN")).thenReturn("token");

        AdminLoginVO vo = service.login(dto());

        assertEquals("token", vo.getAccessToken());
        assertEquals("SUPER_ADMIN", vo.getRole());
        verify(merchantJwtUtil).generateToken(any(), isNull(), anyString());
    }
}
