package com.mall.module.coupon.controller;

import com.mall.common.result.Result;
import com.mall.module.coupon.entity.vo.UserCouponVO;
import com.mall.module.coupon.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user/coupons")
public class UserCouponController {

    private final CouponService couponService;

    public UserCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    public Result<List<UserCouponVO>> listUserCoupons(
            @RequestParam(required = false) String status) {
        Result<List<UserCouponVO>> result = Result.build();
        result.success(couponService.listUserCoupons(status));
        return result;
    }
}
