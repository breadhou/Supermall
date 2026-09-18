package com.mall.module.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mall.common.result.Result;
import com.mall.module.admin.entity.dto.AdminUserPageDTO;
import com.mall.module.admin.entity.dto.UserStatusDTO;
import com.mall.module.admin.entity.vo.AdminStatisticsVO;
import com.mall.module.admin.entity.vo.AdminUserVO;
import com.mall.module.admin.service.AdminStatisticsService;
import com.mall.module.admin.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AdminStatisticsService adminStatisticsService;

    public AdminUserController(AdminUserService adminUserService,
                               AdminStatisticsService adminStatisticsService) {
        this.adminUserService = adminUserService;
        this.adminStatisticsService = adminStatisticsService;
    }

    @GetMapping("/users")
    public Result<IPage<AdminUserVO>> listUsers(@Valid AdminUserPageDTO dto) {
        Result<IPage<AdminUserVO>> result = Result.build();
        result.success(adminUserService.listUsers(dto));
        return result;
    }

    @PutMapping("/users/{id}/status")
    public Result<Void> updateUserStatus(@PathVariable Long id,
                                         @Valid @RequestBody UserStatusDTO dto) {
        adminUserService.updateStatus(id, dto.getStatus());
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }

    @GetMapping("/statistics")
    public Result<AdminStatisticsVO> statistics() {
        Result<AdminStatisticsVO> result = Result.build();
        result.success(adminStatisticsService.overview());
        return result;
    }
}
