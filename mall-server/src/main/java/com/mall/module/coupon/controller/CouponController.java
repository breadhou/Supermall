package com.mall.module.coupon.controller;

import com.mall.common.result.Result;
import com.mall.module.coupon.entity.vo.CouponVO;
import com.mall.module.coupon.entity.vo.UserCouponVO;
import com.mall.module.coupon.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    public Result<List<CouponVO>> listCoupons() {
        Result<List<CouponVO>> result = Result.build();
        result.success(couponService.listCoupons());
        return result;
    }

    @PostMapping("/{couponId}/receive")
    public Result<UserCouponVO> receiveCoupon(@PathVariable Long couponId) {
        Result<UserCouponVO> result = Result.build();
        result.success(couponService.receiveCoupon(couponId));
        return result;
    }
}
