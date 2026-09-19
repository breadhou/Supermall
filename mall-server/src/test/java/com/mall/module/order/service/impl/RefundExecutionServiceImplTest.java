package com.mall.module.order.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.RefundEligibilityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundExecutionServiceImplTest {

    private static final Long ORDER_ID = 9001L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RefundMapper refundMapper;
    @Mock
    private RefundEligibilityService eligibilityService;

    private RefundExecutionServiceImpl service;

    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;

    @BeforeEach
    void setUp() {
        // SnowflakeIdUtil 需要显式注入 workerId/datacenterId，纯单测里没有 Spring 容器，
        // 不替换静态入口的话写路径一调用 nextId() 就抛 IllegalStateException。
        // 与 OrderServiceImplTest 同一处理方式。
        snowflakeIdUtilMock = mockStatic(SnowflakeIdUtil.class);
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(9100L);

        service = new RefundExecutionServiceImpl(orderMapper, refundMapper, eligibilityService);
    }

    @AfterEach
    void tearDown() {
        if (snowflakeIdUtilMock != null) snowflakeIdUtilMock.close();
    }

    private void givenEligible() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(true)
                .setPolicyCode("SEVEN_DAY_NO_REASON")
                .setRefundableAmount(new BigDecimal("199.99")));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(new Order().setId(ORDER_ID).setStatus("RECEIVED")
                        .setTotalAmount(new BigDecimal("199.99")));
    }

    @Test
    void execute_shouldWriteRefundAndAdvanceOrderToRefunded() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.execute(ORDER_ID, "不想要了");

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundMapper).insert(captor.capture());
        assertEquals(new BigDecimal("199.99"), captor.getValue().getAmount());
        assertEquals("REFUNDED", captor.getValue().getStatus());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals("REFUNDED", orderCaptor.getValue().getStatus());
    }

    @Test
    void execute_shouldBeIdempotentWhenRefundAlreadyCompleted() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(
                new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("REFUNDED"));

        service.execute(ORDER_ID, "重复请求");

        // 幂等：不再插入、不再改订单
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    /**
     * 顺序重试的真实形态：check() 会如实报告「已有退款记录」、eligible=false。
     *
     * <p><b>这个用例是前一个用例的对照。</b>上一个用 {@link #givenEligible()} 把 check()
     * stub 成了 eligible=true——而现实中只要退款行存在，check() 绝不会返回 true。
     * 那个 stub 恰好把会拦住它的组件换掉了，所以它能通过**不代表**真实组合能通过。
     * 本用例不 stub check()，直接喂入真实形态，守住守卫条件里的
     * {@code !eligibility.isRefundExists()} 这一半。</p>
     */
    @Test
    void execute_shouldTreatExistingRefundAsRetryNotRejection() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(false)
                .setRefundExists(true)
                .setReason("该订单已有退款记录，不能重复申请"));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(new Order().setId(ORDER_ID).setStatus("RECEIVED")
                        .setTotalAmount(new BigDecimal("199.99")));
        when(refundMapper.selectOne(any())).thenReturn(
                new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("REFUNDED"));

        RefundEligibilityVO vo = service.execute(ORDER_ID, "重复请求");

        // 不得抛 ORDER_NOT_REFUNDABLE
        assertTrue(vo.isRefundExists());
        verify(refundMapper, never()).insert(any(Refund.class));
    }

    /**
     * 顺序重试且既有行仍是 PENDING（旧端点落的）——文案必须与事实相符。
     */
    @Test
    void execute_shouldNotClaimCompletedWhenExistingRefundIsStillPending() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(false)
                .setRefundExists(true)
                .setReason("该订单已有退款记录，不能重复申请"));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(new Order().setId(ORDER_ID).setStatus("RECEIVED")
                        .setTotalAmount(new BigDecimal("199.99")));
        when(refundMapper.selectOne(any())).thenReturn(
                new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("PENDING"));

        RefundEligibilityVO vo = service.execute(ORDER_ID, "重复请求");

        // 钱没退、订单没推进，绝不能说「已完成退款」
        assertNotEquals("该订单已完成退款", vo.getReason());
        assertTrue(vo.getReason().contains("处理中"), vo.getReason());
    }

    /**
     * 并发兜底：复查读到过期快照（返回 null），insert 撞 uk_refund_order。
     * 必须被翻译成幂等返回，而不是漏成 -1 系统异常。
     */
    @Test
    void execute_shouldTranslateDuplicateKeyIntoIdempotentResult() {
        givenEligible();
        Refund winner = new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("REFUNDED");
        // 第一次复查读不到（模拟 REPEATABLE READ 下的过期快照），
        // catch 里的第二次查询读得到（新语句，新快照）
        when(refundMapper.selectOne(any())).thenReturn(null, winner);
        when(refundMapper.insert(any(Refund.class)))
                .thenThrow(new DuplicateKeyException("uk_refund_order"));

        RefundEligibilityVO vo = service.execute(ORDER_ID, "并发重试");

        assertTrue(vo.isRefundExists());
        assertFalse(vo.isEligible());
    }

    @Test
    void execute_shouldRefuseWhenNotEligible() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID).setEligible(false).setReason("不符合政策"));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.execute(ORDER_ID, "试试"));

        assertEquals(ResultStatus.ORDER_NOT_REFUNDABLE, exception.getStatus());
        verify(refundMapper, never()).insert(any(Refund.class));
    }

    @Test
    void execute_shouldIgnoreClientSuppliedAmount() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.execute(ORDER_ID, "我要求退 99999");

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundMapper).insert(captor.capture());
        // 金额来自服务端计算，与用户说法无关
        assertEquals(new BigDecimal("199.99"), captor.getValue().getAmount());
    }

    @Test
    void execute_shouldLockOrderRowBeforeWriting() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.execute(ORDER_ID, "并发测试");

        // 先锁行再写，防并发重复执行
        verify(orderMapper).selectByIdForUpdate(ORDER_ID);
        verify(orderMapper, never()).selectById(anyLong());
    }
}
