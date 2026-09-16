package com.mall.module.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.redis.CouponStockRedisService;
import com.mall.module.coupon.entity.po.Coupon;
import com.mall.module.coupon.entity.po.UserCoupon;
import com.mall.module.coupon.mapper.CouponMapper;
import com.mall.module.coupon.mapper.UserCouponMapper;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    @Mock
    private CouponMapper couponMapper;

    @Mock
    private UserCouponMapper userCouponMapper;

    @Mock
    private CouponStockRedisService couponStockRedisService;

    private CouponServiceImpl couponService;
    private MockedStatic<UserContext> userContextMock;
    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;

    private static final Long USER_ID = 1001L;
    private static final Long COUPON_ID = 2001L;
    private static final Long USER_COUPON_ID = 3001L;

    private Coupon coupon;
    private UserCoupon userCoupon;

    @BeforeEach
    void setUp() {
        userContextMock = mockStatic(UserContext.class);
        snowflakeIdUtilMock = mockStatic(SnowflakeIdUtil.class);
        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(USER_COUPON_ID);

        coupon = new Coupon()
                .setId(COUPON_ID)
                .setName("满100减10")
                .setType("FULL_REDUCTION")
                .setDiscount(new BigDecimal("10.00"))
                .setMinAmount(new BigDecimal("100.00"))
                .setTotal(100)
                .setExpireDay(30)
                .setCreatedAt(LocalDateTime.now().minusDays(1));
        userCoupon = new UserCoupon()
                .setId(USER_COUPON_ID)
                .setUserId(USER_ID)
                .setCouponId(COUPON_ID)
                .setStatus("UNUSED")
                .setCreatedAt(LocalDateTime.now().minusHours(1));

        couponService = new CouponServiceImpl(couponMapper, userCouponMapper, couponStockRedisService);
    }

    @AfterEach
    void tearDown() {
        userContextMock.close();
        snowflakeIdUtilMock.close();
    }

    @Test
    void receiveCoupon_shouldInsertUserCouponAndReserveStock() {
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(couponStockRedisService.reserve(COUPON_ID, 100, 0L)).thenReturn(true);

        var result = couponService.receiveCoupon(COUPON_ID);

        assertEquals(COUPON_ID, result.getCouponId());
        assertEquals("UNUSED", result.getStatus());
        verify(couponStockRedisService).reserve(COUPON_ID, 100, 0L);
        verify(userCouponMapper).insert(any(UserCoupon.class));
    }

    @Test
    void receiveCoupon_shouldBeIdempotentWhenAlreadyOwned() {
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(userCoupon);

        var result = couponService.receiveCoupon(COUPON_ID);

        assertEquals(USER_COUPON_ID, result.getId());
        assertEquals("UNUSED", result.getStatus());
        verify(couponStockRedisService, never()).reserve(anyLong(), any(Integer.class), anyLong());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void receiveCoupon_shouldFailWhenStockIsEmpty() {
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(userCouponMapper.selectCount(any())).thenReturn(100L);
        when(couponStockRedisService.reserve(COUPON_ID, 100, 100L)).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> couponService.receiveCoupon(COUPON_ID)
        );

        assertEquals(ResultStatus.COUPON_STOCK_EMPTY, exception.getStatus());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void receiveCoupon_shouldRollbackRedisWhenDatabaseInsertFails() {
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(couponStockRedisService.reserve(COUPON_ID, 100, 0L)).thenReturn(true);
        doThrow(new IllegalStateException("database unavailable"))
                .when(userCouponMapper).insert(any(UserCoupon.class));

        assertThrows(IllegalStateException.class, () -> couponService.receiveCoupon(COUPON_ID));

        verify(couponStockRedisService).rollback(COUPON_ID);
    }

    @Test
    void useCoupon_shouldApplyFullReductionAndMarkUsed() {
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(userCoupon);
        when(userCouponMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        var result = couponService.useCoupon(COUPON_ID, new BigDecimal("199.99"));

        assertEquals(new BigDecimal("10.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("189.99"), result.getFinalAmount());
        verify(userCouponMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void useCoupon_shouldApplyDiscountFactor() {
        coupon.setType("DISCOUNT").setDiscount(new BigDecimal("0.80")).setMinAmount(BigDecimal.ZERO);
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(userCoupon);
        when(userCouponMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        var result = couponService.useCoupon(COUPON_ID, new BigDecimal("100.00"));

        assertEquals(new BigDecimal("20.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("80.00"), result.getFinalAmount());
    }

    @Test
    void useCoupon_shouldRejectWhenBelowMinimumAmount() {
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(userCoupon);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> couponService.useCoupon(COUPON_ID, new BigDecimal("99.99"))
        );

        assertEquals(ResultStatus.COUPON_NOT_APPLICABLE, exception.getStatus());
        verify(userCouponMapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void useCoupon_shouldMarkExpiredAndReject() {
        userCoupon.setCreatedAt(LocalDateTime.now().minusDays(31));
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(userCoupon);
        when(userCouponMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> couponService.useCoupon(COUPON_ID, new BigDecimal("199.99"))
        );

        assertEquals(ResultStatus.COUPON_EXPIRED, exception.getStatus());
        assertEquals("EXPIRED", userCoupon.getStatus());
        verify(userCouponMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void restoreCoupon_shouldReturnUsedCouponToUnused() {
        userCoupon.setStatus("USED").setUsedAt(LocalDateTime.now());
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(userCoupon);
        when(userCouponMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        couponService.restoreCoupon(COUPON_ID);

        verify(userCouponMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void useCoupon_shouldRejectAlreadyUsedCoupon() {
        userCoupon.setStatus("USED");
        when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon);
        when(userCouponMapper.selectOne(any())).thenReturn(userCoupon);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> couponService.useCoupon(COUPON_ID, new BigDecimal("199.99"))
        );

        assertEquals(ResultStatus.COUPON_ALREADY_USED, exception.getStatus());
        assertNull(userCoupon.getUsedAt());
        verify(userCouponMapper, never()).update(isNull(), any(UpdateWrapper.class));
    }
}
