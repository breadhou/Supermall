package com.mall.module.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.Result;
import com.mall.module.order.entity.dto.CreateOrderDTO;
import com.mall.module.order.entity.dto.OrderPageDTO;
import com.mall.module.order.entity.dto.RefundDTO;
import com.mall.module.order.entity.dto.RefundReasonDTO;
import com.mall.module.order.entity.vo.OrderListVO;
import com.mall.module.order.entity.vo.OrderVO;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.service.OrderService;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundExecutionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    OrderService orderService;

    @Autowired
    RefundEligibilityService refundEligibilityService;

    @Autowired
    RefundExecutionService refundExecutionService;

    @PostMapping
    public Result<OrderVO> createOrder(@Valid @RequestBody CreateOrderDTO dto) {
        OrderVO vo = orderService.createOrder(dto);
        Result<OrderVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @GetMapping
    public Result<Page<OrderListVO>> listOrders(@Valid OrderPageDTO dto) {
        Page<OrderListVO> page = orderService.listOrders(dto);
        Result<Page<OrderListVO>> result = Result.build();
        result.success(page);
        return result;
    }

    @GetMapping("/{id}")
    public Result<OrderVO> getOrderDetail(@PathVariable Long id) {
        OrderVO vo = orderService.getOrderDetail(id);
        Result<OrderVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @PutMapping("/{id}/cancel")
    public Result<Void> cancelOrder(@PathVariable Long id) {
        orderService.cancelOrder(id);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }

    @PutMapping("/{id}/receive")
    public Result<Void> confirmReceipt(@PathVariable Long id) {
        orderService.confirmReceipt(id);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }

    @PostMapping("/{id}/refund")
    public Result<Void> requestRefund(@PathVariable Long id, @Valid @RequestBody RefundDTO dto) {
        orderService.requestRefund(id, dto);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }

    /**
     * 查询该订单的售后资格与可退金额。只读，无副作用。
     *
     * <p><b>{@code refundableAmount} 的语义是重载的，调用方必须结合 {@code eligible}
     * 与 {@code refundExists} 判读。它<b>只要订单存在就一定有值</b>（恒为订单实付金额），
     * 但含义随象限变化：</p>
     *
     * <ul>
     *   <li>{@code eligible=true}：它是<b>可退</b>金额，此刻确实可退；</li>
     *   <li>{@code refundExists=true}（已有退款记录，此时 {@code eligible=false}）：
     *       <b>这个数字不代表现在还能退</b>——它仍是订单实付金额（当前只支持整单退款，
     *       故与那条既有记录的金额相同）。<b>既有行还是 {@code PENDING} 时，这笔钱还没退</b>，
     *       别读成「已退」；</li>
     *   <li>两者皆为 {@code false}（订单状态不符合任何售后政策）：<b>这个数字更不是承诺</b>——
     *       字段仍填了订单实付金额，但该订单当前不可退。
     *       <b>别把这一支读成「超期」</b>：签收超过 7 天<b>不会</b>落到这里——
     *       {@code QUALITY_ISSUE} 对 {@code RECEIVED} 订单无期限兜底，
     *       天数只决定命中哪条政策，不决定有没有政策（见 {@code AfterSalesPolicy}）。</li>
     * </ul>
     *
     * <p>⚠️ <b>本端点区分不了既有记录是「已完成」还是「仍在处理中」</b>——
     * {@code refundExists=true} 时它只报告「有记录」，{@code reason} 也不带状态。
     * 要判断退款走到了哪一步，调 {@code POST /api/orders/{id}/refund/execute}
     * 看它返回的 {@code reason}。</p>
     */
    @GetMapping("/{id}/refund-eligibility")
    public Result<RefundEligibilityVO> refundEligibility(@PathVariable Long id) {
        Result<RefundEligibilityVO> result = Result.build();
        result.success(refundEligibilityService.check(id));
        return result;
    }

    /**
     * 执行退款。幂等——Agent 会重试，重复调用返回既有结论而非报错。
     * 金额由服务端决定，请求体只携带原因。
     *
     * <p><b>两种响应形态，调用方必须都能正确处理</b>（与
     * {@link RefundExecutionService#execute} 的契约一致）：</p>
     *
     * <ul>
     *   <li><b>本次执行了退款</b>：{@code eligible=true}、{@code reason=null}，
     *       {@code refundableAmount} 为本次退款金额，订单已推进到 {@code REFUNDED}；</li>
     *   <li><b>此前已有退款记录、本次未重复执行</b>：{@code eligible=false}、
     *       {@code refundExists=true}。<b>这不是失败</b>，
     *       不要读成「退款没成功」而重试或升级。</li>
     * </ul>
     *
     * <p>⚠️ <b>第二种形态必须再看 {@code reason} 才知道既有记录走到了哪一步，
     * 两者不可混为一谈：</b></p>
     *
     * <ul>
     *   <li>「该订单已完成退款」：既有行已是 {@code REFUNDED}，<b>钱已退、订单已推进</b>；</li>
     *   <li>「该订单已有退款申请在处理中」：既有行仍是 {@code PENDING}（旧端点
     *       {@code POST /api/orders/{id}/refund} 落的），<b>钱没退、订单状态也没推进</b>。
     *       这同样不是失败——它是「已有申请、尚未执行」。</li>
     * </ul>
     *
     * <p>⚠️ 最容易被误读的是第二种形态返回的 {@code eligible=false}：把它当失败，
     * 幂等路径就会在调用方那边被读成错误，本端点做幂等就白做了。</p>
     */
    @PostMapping("/{id}/refund/execute")
    public Result<RefundEligibilityVO> executeRefund(@PathVariable Long id,
                                                     @Valid @RequestBody RefundReasonDTO dto) {
        Result<RefundEligibilityVO> result = Result.build();
        result.success(refundExecutionService.execute(id, dto.getReason()));
        return result;
    }
}
