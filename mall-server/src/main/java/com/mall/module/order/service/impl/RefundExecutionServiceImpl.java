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
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundExecutionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RefundExecutionServiceImpl implements RefundExecutionService {

    private static final String REFUNDED = "REFUNDED";

    private final OrderMapper orderMapper;
    private final RefundMapper refundMapper;
    private final RefundEligibilityService eligibilityService;

    public RefundExecutionServiceImpl(OrderMapper orderMapper,
                                      RefundMapper refundMapper,
                                      RefundEligibilityService eligibilityService) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.eligibilityService = eligibilityService;
    }

    @Override
    @Transactional
    public RefundEligibilityVO execute(Long orderId, String reason) {
        // 先判资格：不符合就直接拒绝，不进入写路径。
        //
        // ⚠️「已有退款记录」**不等于**「不可退」——那是**重试**，必须落到下面的幂等分支
        // 返回既有结果，而不是报错。计划要求「重复调用返回同一结果而不是报错」，
        // 若这里只判 isEligible()，顺序重试会在这一行被 50002 挡掉，幂等分支永远不可达。
        RefundEligibilityVO eligibility = eligibilityService.check(orderId);
        if (!eligibility.isEligible() && !eligibility.isRefundExists()) {
            throw new BusinessException(ResultStatus.ORDER_NOT_REFUNDABLE);
        }

        // 锁订单行，防并发重复执行。
        //
        // ⚠️ 不要指望「锁后再查一次」能看见并发赢家刚提交的退款行：本方法是 @Transactional，
        // 而上面的 check() 里的 selectById 已经建立了本事务的 read view；InnoDB 在
        // REPEATABLE READ 下不会刷新它。selectByIdForUpdate **只刷新被锁的那一行，不刷新快照**。
        // 所以下面的复查可能读到 null，随后撞上 uk_refund_order。
        // **真正的并发裁判是唯一索引**，不是这次复查——见下面的 catch。
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        Refund existing = refundMapper.selectOne(
                new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
        if (existing != null) {
            // 幂等分支：Agent 重试会走到这里。不报错、不重复退款，返回既有结果。
            return idempotentResult(orderId, existing);
        }

        try {
            refundMapper.insert(new Refund()
                    .setId(SnowflakeIdUtil.nextId())
                    .setOrderId(orderId)
                    .setUserId(order.getUserId())
                    // 金额一律取服务端算出的值，绝不用入参
                    .setAmount(eligibility.getRefundableAmount())
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
            //
            // 不会死锁：两个事务都先锁订单行，同一订单的并发请求已在订单行上串行化。
            Refund winner = refundMapper.selectByOrderIdForUpdate(orderId);
            if (winner == null) {
                // 能撞上 uk_refund_order 就说明那行存在，读不到说明约束不是它挡的，别吞异常。
                throw e;
            }
            // 注意：这里**不能**吞掉「订单状态没推进」这件事。赢家事务会推进它；
            // 本事务回滚后，订单状态由赢家负责。
            return idempotentResult(orderId, winner);
        }

        order.setStatus(REFUNDED);
        orderMapper.updateById(order);

        return eligibility;
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
