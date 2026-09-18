package com.mall.module.admin.service.impl;

import com.mall.module.admin.entity.dto.AdminCouponDTO;
import com.mall.module.admin.entity.vo.CouponTemplateVO;
import com.mall.module.coupon.mapper.CouponMapper;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.module.seckill.mapper.SeckillActivityMapper;
import com.mall.module.seckill.mapper.SeckillItemMapper;
import com.mall.module.seckill.service.SeckillService;
import com.mall.common.utils.SnowflakeIdUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMarketingServiceImplTest {

    private static final Long SECKILL_ITEM_ID = 2001L;

    @Mock
    private SeckillActivityMapper seckillActivityMapper;
    @Mock
    private SeckillItemMapper seckillItemMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private SeckillService seckillService;

    private AdminMarketingServiceImpl service;

    /**
     * 雪花 ID 现在是 fail-closed 的：未注入实例身份就不发号。
     * 本类不关心具体取值，只需要「已配置」这个前提；同一组值重复配置是幂等的。
     */
    @BeforeAll
    static void configureSnowflakeIds() {
        SnowflakeIdUtil.configure(0L, 0L);
    }

    @BeforeEach
    void setUp() {
        service = new AdminMarketingServiceImpl(seckillActivityMapper, seckillItemMapper,
                productSkuMapper, couponMapper, seckillService);
    }

    /**
     * 预热是运营动作，只对平台管理员开放——它会把 Redis 库存重置为数据库快照，
     * 挂在 C 端路径上时任何注册用户都能调，是超卖路径。
     */
    @Test
    void preheatSeckillItem_shouldDelegateToSeckillService() {
        service.preheatSeckillItem(SECKILL_ITEM_ID);

        verify(seckillService).preheatStock(SECKILL_ITEM_ID);
    }

    @Test
    void createCoupon_shouldRejectDiscountRatioAboveOne() {
        AdminCouponDTO dto = new AdminCouponDTO();
        dto.setName("坏券");
        dto.setType("DISCOUNT");
        dto.setDiscount(new BigDecimal("1.5"));
        dto.setTotal(10);
        dto.setExpireDay(7);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.mall.common.exception.BusinessException.class, () -> service.createCoupon(dto));
    }

    @Test
    void createCoupon_shouldNormaliseTypeToUpperCase() {
        AdminCouponDTO dto = new AdminCouponDTO();
        dto.setName("满100减10");
        dto.setType("full_reduction");
        dto.setDiscount(new BigDecimal("10.00"));
        dto.setMinAmount(new BigDecimal("100.00"));
        dto.setTotal(100);
        dto.setExpireDay(30);

        CouponTemplateVO vo = service.createCoupon(dto);

        assertEquals("FULL_REDUCTION", vo.getType());
    }
}
