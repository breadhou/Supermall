package com.mall.module.admin.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.admin.entity.dto.AdminCouponDTO;
import com.mall.module.admin.entity.dto.AdminSeckillActivityDTO;
import com.mall.module.admin.entity.vo.CouponTemplateVO;
import com.mall.module.admin.entity.vo.SeckillActivityVO;
import com.mall.module.admin.service.AdminMarketingService;
import com.mall.module.coupon.entity.po.Coupon;
import com.mall.module.coupon.mapper.CouponMapper;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.module.seckill.entity.po.SeckillActivity;
import com.mall.module.seckill.entity.po.SeckillItem;
import com.mall.module.seckill.mapper.SeckillActivityMapper;
import com.mall.module.seckill.mapper.SeckillItemMapper;
import com.mall.module.seckill.service.SeckillService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class AdminMarketingServiceImpl implements AdminMarketingService {

    private static final String NOT_STARTED = "NOT_STARTED";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String ENDED = "ENDED";
    private static final Set<String> COUPON_TYPES = Set.of("FULL_REDUCTION", "DISCOUNT");

    private final SeckillActivityMapper seckillActivityMapper;
    private final SeckillItemMapper seckillItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final CouponMapper couponMapper;
    private final SeckillService seckillService;

    public AdminMarketingServiceImpl(SeckillActivityMapper seckillActivityMapper,
                                     SeckillItemMapper seckillItemMapper,
                                     ProductSkuMapper productSkuMapper,
                                     CouponMapper couponMapper,
                                     SeckillService seckillService) {
        this.seckillActivityMapper = seckillActivityMapper;
        this.seckillItemMapper = seckillItemMapper;
        this.productSkuMapper = productSkuMapper;
        this.couponMapper = couponMapper;
        this.seckillService = seckillService;
    }

    @Override
    public void preheatSeckillItem(Long seckillItemId) {
        seckillService.preheatStock(seckillItemId);
    }

    @Override
    @Transactional
    public SeckillActivityVO createSeckillActivity(AdminSeckillActivityDTO dto) {
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        LocalDateTime now = LocalDateTime.now();
        SeckillActivity activity = new SeckillActivity()
                .setId(SnowflakeIdUtil.nextId())
                .setName(dto.getName())
                .setStartTime(dto.getStartTime())
                .setEndTime(dto.getEndTime())
                .setStatus(deriveStatus(dto, now))
                .setCreatedAt(now);
        seckillActivityMapper.insert(activity);

        List<SeckillActivityVO.ItemVO> items = new ArrayList<>(dto.getItems().size());
        for (AdminSeckillActivityDTO.ItemDTO item : dto.getItems()) {
            ProductSku sku = productSkuMapper.selectById(item.getSkuId());
            if (sku == null) {
                throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
            }

            SeckillItem entity = new SeckillItem()
                    .setId(SnowflakeIdUtil.nextId())
                    .setActivityId(activity.getId())
                    .setSkuId(item.getSkuId())
                    .setSeckillPrice(item.getSeckillPrice())
                    .setStock(item.getStock())
                    .setLimitPerUser(item.getLimitPerUser())
                    .setCreatedAt(now);
            seckillItemMapper.insert(entity);

            items.add(new SeckillActivityVO.ItemVO()
                    .setSeckillItemId(entity.getId())
                    .setSkuId(entity.getSkuId())
                    .setSeckillPrice(entity.getSeckillPrice())
                    .setStock(entity.getStock())
                    .setLimitPerUser(entity.getLimitPerUser()));
        }

        return new SeckillActivityVO()
                .setId(activity.getId())
                .setName(activity.getName())
                .setStartTime(activity.getStartTime())
                .setEndTime(activity.getEndTime())
                .setStatus(activity.getStatus())
                .setItems(items);
    }

    @Override
    public CouponTemplateVO createCoupon(AdminCouponDTO dto) {
        String type = dto.getType().toUpperCase();
        if (!COUPON_TYPES.contains(type)) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }
        // 折扣比例必须是 0~1，否则 useCoupon 计算时会抛状态错误
        if ("DISCOUNT".equals(type)
                && (dto.getDiscount().compareTo(BigDecimal.ZERO) <= 0
                    || dto.getDiscount().compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        Coupon coupon = new Coupon()
                .setId(SnowflakeIdUtil.nextId())
                .setName(dto.getName())
                .setType(type)
                .setDiscount(dto.getDiscount())
                .setMinAmount(dto.getMinAmount() == null ? BigDecimal.ZERO : dto.getMinAmount())
                .setTotal(dto.getTotal())
                .setExpireDay(dto.getExpireDay())
                .setCreatedAt(LocalDateTime.now());
        couponMapper.insert(coupon);

        return new CouponTemplateVO()
                .setId(coupon.getId())
                .setName(coupon.getName())
                .setType(coupon.getType())
                .setDiscount(coupon.getDiscount())
                .setMinAmount(coupon.getMinAmount())
                .setTotal(coupon.getTotal())
                .setExpireDay(coupon.getExpireDay())
                .setCreatedAt(coupon.getCreatedAt());
    }

    /** status 仅用于展示，秒杀路径实际按 start_time / end_time 判断。 */
    private String deriveStatus(AdminSeckillActivityDTO dto, LocalDateTime now) {
        if (now.isBefore(dto.getStartTime())) {
            return NOT_STARTED;
        }
        return now.isAfter(dto.getEndTime()) ? ENDED : IN_PROGRESS;
    }
}
