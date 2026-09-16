package com.mall.module.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.redis.CouponStockRedisService;
import com.mall.module.coupon.entity.po.Coupon;
import com.mall.module.coupon.entity.po.UserCoupon;
import com.mall.module.coupon.entity.vo.CouponApplyResult;
import com.mall.module.coupon.entity.vo.CouponVO;
import com.mall.module.coupon.entity.vo.UserCouponVO;
import com.mall.module.coupon.mapper.CouponMapper;
import com.mall.module.coupon.mapper.UserCouponMapper;
import com.mall.module.coupon.service.CouponService;
import com.mall.security.utils.UserContext;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CouponServiceImpl implements CouponService {

    private static final String FULL_REDUCTION = "FULL_REDUCTION";
    private static final String DISCOUNT = "DISCOUNT";
    private static final String UNUSED = "UNUSED";
    private static final String USED = "USED";
    private static final String EXPIRED = "EXPIRED";

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponStockRedisService couponStockRedisService;

    public CouponServiceImpl(CouponMapper couponMapper,
                             UserCouponMapper userCouponMapper,
                             CouponStockRedisService couponStockRedisService) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.couponStockRedisService = couponStockRedisService;
    }

    @Override
    public List<CouponVO> listCoupons() {
        List<Coupon> coupons = couponMapper.selectList(
                new LambdaQueryWrapper<Coupon>().orderByDesc(Coupon::getCreatedAt)
        );
        List<CouponVO> result = new ArrayList<>(coupons.size());
        for (Coupon coupon : coupons) {
            result.add(toCouponVO(coupon, getRemainingStock(coupon)));
        }
        return result;
    }

    @Override
    public UserCouponVO receiveCoupon(Long couponId) {
        Long userId = currentUserId();
        Coupon coupon = getCoupon(couponId);

        UserCoupon existing = findUserCoupon(userId, couponId);
        if (existing != null) {
            expireIfNecessary(existing, coupon, LocalDateTime.now());
            return toUserCouponVO(existing, coupon);
        }

        long claimed = userCouponMapper.selectCount(
                new LambdaQueryWrapper<UserCoupon>().eq(UserCoupon::getCouponId, couponId)
        );
        boolean reserved = false;
        try {
            if (!couponStockRedisService.reserve(couponId, coupon.getTotal(), claimed)) {
                throw new BusinessException(ResultStatus.COUPON_STOCK_EMPTY);
            }
            reserved = true;

            UserCoupon userCoupon = new UserCoupon()
                    .setId(SnowflakeIdUtil.nextId())
                    .setUserId(userId)
                    .setCouponId(couponId)
                    .setStatus(UNUSED)
                    .setCreatedAt(LocalDateTime.now());
            userCouponMapper.insert(userCoupon);
            return toUserCouponVO(userCoupon, coupon);
        } catch (DataIntegrityViolationException exception) {
            if (reserved) {
                couponStockRedisService.rollback(couponId);
            }
            // A concurrent request may have won the unique key.  Returning
            // the persisted row makes retries idempotent.
            UserCoupon concurrent = findUserCoupon(userId, couponId);
            if (concurrent != null) {
                return toUserCouponVO(concurrent, coupon);
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (reserved) {
                couponStockRedisService.rollback(couponId);
            }
            throw exception;
        }
    }

    @Override
    public List<UserCouponVO> listUserCoupons(String status) {
        Long userId = currentUserId();
        expireUserCoupons(userId);

        LambdaQueryWrapper<UserCoupon> wrapper = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getCreatedAt);
        if (status != null && !status.isBlank()) {
            String normalizedStatus = status.toUpperCase();
            if (!UNUSED.equals(normalizedStatus)
                    && !USED.equals(normalizedStatus)
                    && !EXPIRED.equals(normalizedStatus)) {
                throw new BusinessException(ResultStatus.PARAM_ERROR);
            }
            wrapper.eq(UserCoupon::getStatus, normalizedStatus);
        }

        List<UserCoupon> userCoupons = userCouponMapper.selectList(wrapper);
        if (userCoupons.isEmpty()) {
            return List.of();
        }

        Map<Long, Coupon> couponMap = loadCoupons(userCoupons);
        return userCoupons.stream()
                .filter(userCoupon -> couponMap.containsKey(userCoupon.getCouponId()))
                .map(userCoupon -> toUserCouponVO(userCoupon, couponMap.get(userCoupon.getCouponId())))
                .toList();
    }

    @Override
    @Transactional
    public CouponApplyResult useCoupon(Long couponId, BigDecimal orderAmount) {
        if (orderAmount == null || orderAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        Long userId = currentUserId();
        Coupon coupon = getCoupon(couponId);
        UserCoupon userCoupon = findUserCoupon(userId, couponId);
        if (userCoupon == null) {
            throw new BusinessException(ResultStatus.COUPON_NOT_OWNED);
        }
        if (USED.equals(userCoupon.getStatus())) {
            throw new BusinessException(ResultStatus.COUPON_ALREADY_USED);
        }
        if (!UNUSED.equals(userCoupon.getStatus())) {
            throw new BusinessException(ResultStatus.COUPON_STATUS_ERROR);
        }
        if (isExpired(userCoupon, coupon, LocalDateTime.now())) {
            markExpired(userCoupon);
            throw new BusinessException(ResultStatus.COUPON_EXPIRED);
        }
        if (coupon.getMinAmount() != null && orderAmount.compareTo(coupon.getMinAmount()) < 0) {
            throw new BusinessException(ResultStatus.COUPON_NOT_APPLICABLE);
        }

        BigDecimal discountAmount = calculateDiscount(coupon, orderAmount);
        BigDecimal finalAmount = orderAmount.subtract(discountAmount)
                .setScale(2, RoundingMode.HALF_UP);

        UpdateWrapper<UserCoupon> updateWrapper = new UpdateWrapper<UserCoupon>()
                .eq("id", userCoupon.getId())
                .eq("user_id", userId)
                .eq("status", UNUSED)
                .set("status", USED)
                .set("used_at", LocalDateTime.now());
        if (userCouponMapper.update(null, updateWrapper) != 1) {
            throw new BusinessException(ResultStatus.COUPON_STATUS_ERROR);
        }

        userCoupon.setStatus(USED).setUsedAt(LocalDateTime.now());
        return new CouponApplyResult(orderAmount, discountAmount, finalAmount);
    }

    @Override
    @Transactional
    public void restoreCoupon(Long couponId) {
        if (couponId == null) {
            return;
        }
        Long userId = currentUserId();
        Coupon coupon = couponMapper.selectById(couponId);
        UserCoupon userCoupon = findUserCoupon(userId, couponId);
        if (coupon == null || userCoupon == null || !USED.equals(userCoupon.getStatus())) {
            return;
        }
        if (isExpired(userCoupon, coupon, LocalDateTime.now())) {
            markExpired(userCoupon);
            return;
        }

        UpdateWrapper<UserCoupon> updateWrapper = new UpdateWrapper<UserCoupon>()
                .eq("id", userCoupon.getId())
                .eq("user_id", userId)
                .eq("status", USED)
                .set("status", UNUSED)
                .set("used_at", null);
        userCouponMapper.update(null, updateWrapper);
    }

    @Override
    @Scheduled(fixedDelayString = "${mall.coupon.expiration-fixed-delay-ms:60000}")
    public void expireCoupons() {
        List<UserCoupon> userCoupons = userCouponMapper.selectList(
                new LambdaQueryWrapper<UserCoupon>().eq(UserCoupon::getStatus, UNUSED)
        );
        if (userCoupons.isEmpty()) {
            return;
        }
        Map<Long, Coupon> couponMap = loadCoupons(userCoupons);
        LocalDateTime now = LocalDateTime.now();
        for (UserCoupon userCoupon : userCoupons) {
            Coupon coupon = couponMap.get(userCoupon.getCouponId());
            if (coupon != null && isExpired(userCoupon, coupon, now)) {
                markExpired(userCoupon);
            }
        }
    }

    private void expireUserCoupons(Long userId) {
        List<UserCoupon> userCoupons = userCouponMapper.selectList(
                new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, userId)
                        .eq(UserCoupon::getStatus, UNUSED)
        );
        if (userCoupons.isEmpty()) {
            return;
        }
        Map<Long, Coupon> couponMap = loadCoupons(userCoupons);
        LocalDateTime now = LocalDateTime.now();
        for (UserCoupon userCoupon : userCoupons) {
            Coupon coupon = couponMap.get(userCoupon.getCouponId());
            if (coupon != null && isExpired(userCoupon, coupon, now)) {
                markExpired(userCoupon);
            }
        }
    }

    private BigDecimal calculateDiscount(Coupon coupon, BigDecimal orderAmount) {
        if (FULL_REDUCTION.equalsIgnoreCase(coupon.getType())) {
            if (coupon.getDiscount() == null || coupon.getDiscount().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(ResultStatus.COUPON_STATUS_ERROR);
            }
            return coupon.getDiscount().min(orderAmount).setScale(2, RoundingMode.HALF_UP);
        }
        if (DISCOUNT.equalsIgnoreCase(coupon.getType())) {
            BigDecimal factor = coupon.getDiscount();
            if (factor == null || factor.compareTo(BigDecimal.ZERO) < 0
                    || factor.compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException(ResultStatus.COUPON_STATUS_ERROR);
            }
            return orderAmount.multiply(BigDecimal.ONE.subtract(factor))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        throw new BusinessException(ResultStatus.COUPON_STATUS_ERROR);
    }

    private Coupon getCoupon(Long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BusinessException(ResultStatus.COUPON_NOT_EXIST);
        }
        return coupon;
    }

    private UserCoupon findUserCoupon(Long userId, Long couponId) {
        return userCouponMapper.selectOne(
                new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, userId)
                        .eq(UserCoupon::getCouponId, couponId)
        );
    }

    private Map<Long, Coupon> loadCoupons(List<UserCoupon> userCoupons) {
        Map<Long, Coupon> result = new HashMap<>();
        for (UserCoupon userCoupon : userCoupons) {
            Coupon coupon = couponMapper.selectById(userCoupon.getCouponId());
            if (coupon != null) {
                result.put(coupon.getId(), coupon);
            }
        }
        return result;
    }

    private long getRemainingStock(Coupon coupon) {
        try {
            Long stock = couponStockRedisService.getStock(coupon.getId());
            if (stock != null) {
                return Math.max(0L, stock);
            }
        } catch (RuntimeException ignored) {
            // Listing coupons remains available when Redis is temporarily
            // unavailable; the claim path still fails closed.
        }
        Long claimed = userCouponMapper.selectCount(
                new LambdaQueryWrapper<UserCoupon>().eq(UserCoupon::getCouponId, coupon.getId())
        );
        return Math.max(0L, (long) coupon.getTotal() - claimed);
    }

    private boolean isExpired(UserCoupon userCoupon, Coupon coupon, LocalDateTime now) {
        if (userCoupon.getCreatedAt() == null || coupon.getExpireDay() == null) {
            return false;
        }
        return !now.isBefore(userCoupon.getCreatedAt().plusDays(coupon.getExpireDay()));
    }

    private void expireIfNecessary(UserCoupon userCoupon, Coupon coupon, LocalDateTime now) {
        if (UNUSED.equals(userCoupon.getStatus()) && isExpired(userCoupon, coupon, now)) {
            markExpired(userCoupon);
        }
    }

    private void markExpired(UserCoupon userCoupon) {
        UpdateWrapper<UserCoupon> updateWrapper = new UpdateWrapper<UserCoupon>()
                .eq("id", userCoupon.getId())
                .eq("status", UNUSED)
                .set("status", EXPIRED);
        userCouponMapper.update(null, updateWrapper);
        userCoupon.setStatus(EXPIRED);
    }

    private CouponVO toCouponVO(Coupon coupon, long remaining) {
        CouponVO vo = new CouponVO();
        BeanUtils.copyProperties(coupon, vo);
        vo.setRemaining(remaining);
        return vo;
    }

    private UserCouponVO toUserCouponVO(UserCoupon userCoupon, Coupon coupon) {
        UserCouponVO vo = new UserCouponVO();
        BeanUtils.copyProperties(userCoupon, vo);
        if (coupon != null) {
            vo.setName(coupon.getName());
            vo.setType(coupon.getType());
            vo.setDiscount(coupon.getDiscount());
            vo.setMinAmount(coupon.getMinAmount());
            if (userCoupon.getCreatedAt() != null && coupon.getExpireDay() != null) {
                vo.setExpiresAt(userCoupon.getCreatedAt().plusDays(coupon.getExpireDay()));
            }
        }
        return vo;
    }

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultStatus.USER_NOT_EXIST);
        }
        return userId;
    }
}
