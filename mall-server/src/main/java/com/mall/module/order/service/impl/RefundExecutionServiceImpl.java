package com.mall.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.AfterSalesPolicyCatalog;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundEligibilityEvaluator;
import com.mall.module.order.service.RefundEligibilityEvaluator.Assessment;
import com.mall.module.order.service.RefundExecutionService;
import com.mall.security.utils.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Objects;

@Service
public class RefundExecutionServiceImpl implements RefundExecutionService {

    private static final String REFUNDED = "REFUNDED";

    private final OrderMapper orderMapper;
    private final RefundMapper refundMapper;
    private final RefundEligibilityService eligibilityService;
    private final AfterSalesPolicyCatalog policyCatalog;
    private final RefundEligibilityEvaluator evaluator;

    @Autowired
    public RefundExecutionServiceImpl(OrderMapper orderMapper,
                                      RefundMapper refundMapper,
                                      RefundEligibilityService eligibilityService,
                                      AfterSalesPolicyCatalog policyCatalog,
                                      RefundEligibilityEvaluator evaluator) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.eligibilityService = eligibilityService;
        this.policyCatalog = policyCatalog;
        this.evaluator = evaluator;
    }

    public RefundExecutionServiceImpl(OrderMapper orderMapper,
                                      RefundMapper refundMapper,
                                      RefundEligibilityService eligibilityService) {
        this(orderMapper, refundMapper, eligibilityService,
                new AfterSalesPolicyCatalog(), new RefundEligibilityEvaluator());
    }

    @Override
    @Transactional
    public RefundEligibilityVO execute(Long orderId, String reason) {
        return execute(orderId, reason, null, null);
    }

    @Override
    @Transactional
    public RefundEligibilityVO execute(Long orderId, String reason,
                                       String expectedCatalogFingerprint, String expectedPolicyCode) {
        if ((expectedCatalogFingerprint == null) != (expectedPolicyCode == null)
                || expectedCatalogFingerprint != null && expectedCatalogFingerprint.isBlank()
                || expectedPolicyCode != null && expectedPolicyCode.isBlank()) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        // This ordinary read establishes the repeatable-read view. Defer a denial until
        // the locked order and a possible concurrent refund winner have been checked.
        RefundEligibilityVO eligibility = eligibilityService.check(orderId);
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null || !Objects.equals(UserContext.getUserId(), order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        Refund existing = refundMapper.selectOne(
                new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
        if (existing != null) {
            return idempotentResult(orderId, existing);
        }

        Assessment locked = evaluator.assess(order);
        String lockedPolicyCode = locked.policy() == null ? null : locked.policy().name();
        String currentFingerprint = policyCatalog.currentSnapshot().getFingerprint();
        ResultStatus rejection = null;
        if (!eligibility.isEligible()) {
            rejection = ResultStatus.ORDER_NOT_REFUNDABLE;
        } else if (locked.policy() == null
                || !Objects.equals(eligibility.getOrderStatus(), locked.orderStatus())
                || !sameAmount(eligibility.getRefundableAmount(), locked.refundableAmount())
                || !Objects.equals(eligibility.getPolicyCode(), lockedPolicyCode)
                || !Objects.equals(eligibility.getCatalogFingerprint(), currentFingerprint)
                || expectedCatalogFingerprint != null
                    && (!expectedCatalogFingerprint.equals(currentFingerprint)
                        || !expectedPolicyCode.equals(lockedPolicyCode))) {
            rejection = ResultStatus.REFUND_REVIEW_STALE;
        }
        if (rejection != null) {
            // An old read view can hide a winner that committed while the order lock
            // was awaited. Only this rejection path takes a refund-key locking read.
            Refund winner = refundMapper.selectByOrderIdForUpdate(orderId);
            if (winner != null) {
                return idempotentResult(orderId, winner);
            }
            throw new BusinessException(rejection);
        }

        try {
            refundMapper.insert(new Refund()
                    .setId(SnowflakeIdUtil.nextId())
                    .setOrderId(orderId)
                    .setUserId(order.getUserId())
                    // 金额一律取服务端算出的值，绝不用入参
                    .setAmount(locked.refundableAmount())
                    .setReason(reason)
                    .setStatus(REFUNDED)
                    .setCreatedAt(LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            // 并发重试的兜底：另一个事务抢先插入了同一订单的退款行，唯一索引已经
            // 替我们挡住了重复退款，这里只需把它翻译成业务语义。
            // 与 OrderServiceImpl.requestRefund 的写法同源（Task 3 已批准落地）。
            //
            // ⚠️ 这里**必须**用锁定读，普通 SELECT 一定读不到赢家——两者是同一个原因的两面：
            // 能进到这个 catch，恰恰说明本事务的 read view 早于赢家提交（若 read view 更晚，
            // 上方的复查就已经看见该行并提前返回，根本进不来）。而普通 SELECT 在本事务内
            // 复用那个旧 read view，于是必然读到 null、必然把 DuplicateKeyException 漏成
            // -1 系统异常——正是本分支要避免的结果。锁定读读的是最新已提交版本，不受快照约束。
            // （2026-09-19 修订，见 Task 5 修订说明）
            //
            // 不会死锁：两个事务都先锁订单行，同一订单的并发请求已在订单行上串行化。
            Refund winner = refundMapper.selectByOrderIdForUpdate(orderId);
            if (winner == null) {
                // 能撞上 uk_refund_order 就说明那行存在，读不到说明约束不是它挡的，别吞异常。
                throw e;
            }
            // 注意：这里**不能**吞掉「订单状态没推进」这件事。但「谁推进」取决于赢家是谁：
            // 赢家若是本服务，订单状态已由它推进到 REFUNDED；赢家若是旧端点
            // （POST /api/orders/{id}/refund），它只落一行 PENDING，**本就不该**推进订单状态。
            // 两种情况都不需要本事务补写——本事务只是没有写入成功，正常返回即提交，
            // 而提交一个什么都没写的事务等于无操作。
            return idempotentResult(orderId, winner);
        }

        order.setStatus(REFUNDED);
        orderMapper.updateById(order);

        return eligibility.setRefundableAmount(locked.refundableAmount());
    }

    private boolean sameAmount(BigDecimal readAmount, BigDecimal lockedAmount) {
        return readAmount != null && lockedAmount != null
                && readAmount.compareTo(lockedAmount) == 0;
    }

    /**
     * 幂等返回：把一条既有退款记录翻译成调用方能读懂的结论。
     *
     * <p><b>文案按状态区分是必须的，不是措辞讲究。</b>旧端点
     * {@code POST /api/orders/{id}/refund} 落的行是 {@code PENDING}——钱没退、订单状态
     * 也没推进。若对它也回「该订单已完成退款」，那就是一句<b>与事实相反</b>的话，
     * 而这句话会经 MCP 传到 agent，成为给用户的解释。</p>
     */
    private RefundEligibilityVO idempotentResult(Long orderId, Refund existing) {
        boolean completed = REFUNDED.equals(existing.getStatus());
        return new RefundEligibilityVO()
                .setOrderId(orderId)
                .setEligible(false)
                .setRefundExists(true)
                .setRefundableAmount(existing.getAmount())
                .setReason(completed ? "该订单已完成退款" : "该订单已有退款申请在处理中");
    }
}
