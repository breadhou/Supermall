package com.mall.module.coupon.service;

import com.mall.module.coupon.entity.vo.CouponApplyResult;
import com.mall.module.coupon.entity.vo.CouponVO;
import com.mall.module.coupon.entity.vo.UserCouponVO;

import java.math.BigDecimal;
import java.util.List;

public interface CouponService {

    List<CouponVO> listCoupons();

    UserCouponVO receiveCoupon(Long couponId);

    List<UserCouponVO> listUserCoupons(String status);

    CouponApplyResult useCoupon(Long couponId, BigDecimal orderAmount);

    void restoreCoupon(Long couponId);

    /** Expire unused coupons whose receive-time validity window has elapsed. */
    void expireCoupons();
}
