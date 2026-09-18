package com.mall.module.admin.controller;

import com.mall.common.result.Result;
import com.mall.module.admin.entity.dto.AdminLoginDTO;
import com.mall.module.admin.entity.vo.AdminLoginVO;
import com.mall.module.admin.service.AdminAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public Result<AdminLoginVO> login(@Valid @RequestBody AdminLoginDTO dto) {
        Result<AdminLoginVO> result = Result.build();
        result.success(adminAuthService.login(dto));
        return result;
    }
}
