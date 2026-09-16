package com.mall.module.coupon.controller;

import com.mall.common.enums.ResultStatus;
import com.mall.common.result.Result;
import com.mall.module.coupon.entity.vo.CouponVO;
import com.mall.module.coupon.entity.vo.UserCouponVO;
import com.mall.module.coupon.service.CouponService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponControllerTest {

    @Mock
    private CouponService couponService;

    @InjectMocks
    private CouponController couponController;

    @InjectMocks
    private UserCouponController userCouponController;

    @Test
    void listCoupons_shouldReturnSuccessResult() {
        CouponVO coupon = new CouponVO();
        coupon.setId(2001L);
        when(couponService.listCoupons()).thenReturn(List.of(coupon));

        Result<List<CouponVO>> result = couponController.listCoupons();

        assertSuccess(result);
        assertEquals(1, result.getData().size());
    }

    @Test
    void receiveCoupon_shouldDelegateToService() {
        UserCouponVO userCoupon = new UserCouponVO();
        userCoupon.setCouponId(2001L);
        when(couponService.receiveCoupon(2001L)).thenReturn(userCoupon);

        Result<UserCouponVO> result = couponController.receiveCoupon(2001L);

        assertSuccess(result);
        assertEquals(2001L, result.getData().getCouponId());
        verify(couponService).receiveCoupon(2001L);
    }

    @Test
    void listUserCoupons_shouldPassStatusFilter() {
        when(couponService.listUserCoupons("USED")).thenReturn(List.of(new UserCouponVO()));

        Result<List<UserCouponVO>> result = userCouponController.listUserCoupons("USED");

        assertSuccess(result);
        assertNotNull(result.getData());
        verify(couponService).listUserCoupons("USED");
    }

    private void assertSuccess(Result<?> result) {
        assertNotNull(result);
        assertEquals(ResultStatus.SUCCESS, result.getStatus());
        assertEquals(0, result.getCode());
    }
}
