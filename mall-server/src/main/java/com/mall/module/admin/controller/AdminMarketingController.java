package com.mall.module.admin.controller;

import com.mall.common.result.Result;
import com.mall.module.admin.entity.dto.AdminCouponDTO;
import com.mall.module.admin.entity.dto.AdminSeckillActivityDTO;
import com.mall.module.admin.entity.vo.CouponTemplateVO;
import com.mall.module.admin.entity.vo.SeckillActivityVO;
import com.mall.module.admin.service.AdminMarketingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 预热秒杀商品库存。
     *
     * <p>该操作原本挂在 C 端 {@code /api/seckill/{itemId}/preheat} 上，只要求
     * 已登录即可调用，而它的效果是重置 Redis 库存——秒杀进行中调用会制造超卖，
     * 因此收归管理端。</p>
     */
    @PostMapping("/seckill/items/{itemId}/preheat")
    public Result<Void> preheatSeckillItem(@PathVariable Long itemId) {
        adminMarketingService.preheatSeckillItem(itemId);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }
}
