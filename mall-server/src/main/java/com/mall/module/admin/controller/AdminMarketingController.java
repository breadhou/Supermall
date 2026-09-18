package com.mall.module.admin.controller;

import com.mall.common.result.Result;
import com.mall.module.admin.entity.dto.AdminCouponDTO;
import com.mall.module.admin.entity.dto.AdminSeckillActivityDTO;
import com.mall.module.admin.entity.vo.CouponTemplateVO;
import com.mall.module.admin.entity.vo.SeckillActivityVO;
import com.mall.module.admin.service.AdminMarketingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminMarketingController {

    private final AdminMarketingService adminMarketingService;

    public AdminMarketingController(AdminMarketingService adminMarketingService) {
        this.adminMarketingService = adminMarketingService;
    }

    @PostMapping("/seckill/activities")
    public Result<SeckillActivityVO> createActivity(
            @Valid @RequestBody AdminSeckillActivityDTO dto) {
        Result<SeckillActivityVO> result = Result.build();
        result.success(adminMarketingService.createSeckillActivity(dto));
        return result;
    }

    @PostMapping("/coupons")
    public Result<CouponTemplateVO> createCoupon(@Valid @RequestBody AdminCouponDTO dto) {
        Result<CouponTemplateVO> result = Result.build();
        result.success(adminMarketingService.createCoupon(dto));
        return result;
    }
}
