package com.mall.module.order.service;

import com.mall.module.order.entity.vo.RefundEligibilityVO;

public interface RefundExecutionService {

    /**
     * 执行退款。幂等：重复调用不报错、不重复退款。
     *
     * <p>返回值是 {@link RefundEligibilityVO}，但它描述的是<b>本次调用后的结论</b>，
     * 不是「当前是否可退」的查询结论。两种形态：</p>
     *
     * <ul>
     *   <li><b>本次执行了退款</b>：{@code eligible=true}、{@code reason=null}，
     *       {@code refundableAmount} 为<b>本次退款金额</b>，订单已推进到 {@code REFUNDED}；
     *       {@code refundExists=false} 说的是<b>本次调用之前</b>没有既有退款记录——它是写前的事实，
     *       不代表此刻的状态。</li>
     *   <li><b>此前已有退款记录，本次未重复执行</b>：{@code eligible=false}、
     *       {@code refundExists=true}，{@code refundableAmount} 为<b>该既有记录的金额</b>。
     *       调用方据此判断「已经退过了」，<b>不要当成失败</b>。</li>
     * </ul>
     *
     * <p>第二种形态的 {@code reason} 文案按<b>既有行的状态</b>区分，两者不可混为一谈：
     * 行已是 {@code REFUNDED} 则是「该订单已完成退款」（钱已退、订单已推进）；
     * 行仍是 {@code PENDING}（旧端点 {@code POST /api/orders/{id}/refund} 落的）则是
     * 「该订单已有退款申请在处理中」——<b>钱没退、订单状态也没推进</b>。</p>
     *
     * <p>{@link RefundEligibilityVO} 的字段注释是按<b>查询</b>语义写的；在本方法的返回值里，
     * 请以本方法陈述的两种形态为准。</p>
     *
     * @param reason 用户给出的退款原因，仅作记录，不影响金额
     */
    RefundEligibilityVO execute(Long orderId, String reason);
}
