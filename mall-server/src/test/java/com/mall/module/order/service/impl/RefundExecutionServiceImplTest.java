package com.mall.module.order.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.AfterSalesPolicyCatalog;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundExecutionService;
import com.mall.security.utils.UserContext;
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
import java.time.LocalDateTime;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundExecutionServiceImplTest {

    private static final Long ORDER_ID = 9001L;
    private static final Long USER_ID = 1001L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RefundMapper refundMapper;
    @Mock
    private RefundEligibilityService eligibilityService;

    private RefundExecutionServiceImpl service;

    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;
    private MockedStatic<UserContext> userContextMock;

    @BeforeEach
    void setUp() {
        // SnowflakeIdUtil 需要显式注入 workerId/datacenterId，纯单测里没有 Spring 容器，
        // 不替换静态入口的话写路径一调用 nextId() 就抛 IllegalStateException。
        // 与 OrderServiceImplTest 同一处理方式。
        snowflakeIdUtilMock = mockStatic(SnowflakeIdUtil.class);
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(9100L);
        userContextMock = mockStatic(UserContext.class);
        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);

        service = new RefundExecutionServiceImpl(orderMapper, refundMapper, eligibilityService);
    }

    @AfterEach
    void tearDown() {
        if (snowflakeIdUtilMock != null) snowflakeIdUtilMock.close();
        if (userContextMock != null) userContextMock.close();
    }

    private void givenEligible() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(true)
                .setCatalogFingerprint(currentFingerprint())
                .setOrderStatus("RECEIVED")
                .setPolicyCode("SEVEN_DAY_NO_REASON")
                .setRefundableAmount(new BigDecimal("199.99")));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(new Order().setId(ORDER_ID).setUserId(USER_ID).setStatus("RECEIVED")
                        .setTotalAmount(new BigDecimal("199.99")));
    }

    private String currentFingerprint() {
        return new AfterSalesPolicyCatalog().currentSnapshot().getFingerprint();
    }

    private String differentFingerprint(String fingerprint) {
        return (fingerprint.startsWith("0") ? "1" : "0") + fingerprint.substring(1);
    }

    private Order lockedOrder(String status, String amount, Long userId) {
        return new Order().setId(ORDER_ID).setUserId(userId).setStatus(status)
                .setTotalAmount(new BigDecimal(amount))
                .setCreatedAt(LocalDateTime.now().minusDays(2));
    }

    private void givenReviewedEligible(String readStatus, String readAmount,
                                       String lockedStatus, String lockedAmount) {
        String policyCode = AfterSalesPolicy.resolve(readStatus, 2).name();
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(true)
                .setCatalogFingerprint(currentFingerprint())
                .setOrderStatus(readStatus)
                .setPolicyCode(policyCode)
                .setRefundableAmount(new BigDecimal(readAmount)));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(lockedOrder(lockedStatus, lockedAmount, USER_ID));
    }

    /** Invokes the Task 2 overload without making the RED tests fail at test compilation. */
    private RefundEligibilityVO executeReviewed(String fingerprint, String policyCode) {
        Method method;
        try {
            method = RefundExecutionService.class.getMethod(
                    "execute", Long.class, String.class, String.class, String.class);
        } catch (NoSuchMethodException e) {
            fail("RefundExecutionService must expose the reviewed four-argument overload", e);
            return null;
        }
        try {
            return (RefundEligibilityVO) method.invoke(
                    service, ORDER_ID, "测试理由", fingerprint, policyCode);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
                .thenReturn(new Order().setId(ORDER_ID).setUserId(USER_ID).setStatus("RECEIVED")
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
                .thenReturn(new Order().setId(ORDER_ID).setUserId(USER_ID).setStatus("RECEIVED")
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
    void uniqueKeyLoserStillReturnsWinner() {
        givenEligible();
        Refund winner = new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("REFUNDED")
                .setAmount(new BigDecimal("199.99"));
        // 上方复查读不到：能进 catch 就说明本事务的 read view 早于赢家提交，
        // 普通 SELECT 复用旧快照，必然读到 null（这不只是「模拟」，而是必现）
        when(refundMapper.selectOne(any())).thenReturn(null);
        // catch 里的锁定读读最新已提交版本，不受本事务快照约束
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(winner);
        when(refundMapper.insert(any(Refund.class)))
                .thenThrow(new DuplicateKeyException("uk_refund_order"));

        RefundEligibilityVO vo = service.execute(ORDER_ID, "并发重试");

        assertTrue(vo.isRefundExists());
        assertFalse(vo.isEligible());
        // 行为断言钉不住这次修复（读被 stub 掉了，无论走锁定读还是普通 SELECT 都能返回 winner），
        // 只有交互断言能钉住：catch 里必须走锁定读，普通 SELECT 在旧 read view 下必然读不到赢家。
        verify(refundMapper).selectByOrderIdForUpdate(ORDER_ID);
        // 下面两条把「用的是锁定读的**结果**」也钉住（2026-09-19 补）。
        // 只有上面那条 verify 是不够的：把返回值丢掉、改用硬编码的 Refund 构造结论，
        // 8/8 照样全绿——**变异测试实测确认过这个缺口**，这两条才把它堵上。
        assertEquals(new BigDecimal("199.99"), vo.getRefundableAmount());
        assertEquals("该订单已完成退款", vo.getReason());
    }

    /**
     * catch 的另一条出口：撞上的**不是**这条唯一索引时，必须原样抛出，不得吞成业务结论。
     *
     * <p>2026-09-19 补。catch 有两条出口，上面那个用例守的是「翻译成业务结论」，
     * 本用例守的是「翻译不了就别把真故障说成业务结论」——K-21 第 2 条记的正是这个家族
     * （撞主键被误报成「已有退款记录」）。</p>
     */
    @Test
    void execute_shouldRethrowWhenTheDuplicateIsNotTheOrderUniqueIndex() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(null);
        when(refundMapper.insert(any(Refund.class)))
                .thenThrow(new DuplicateKeyException("PRIMARY"));

        assertThrows(DuplicateKeyException.class, () -> service.execute(ORDER_ID, "并发重试"));
    }

    @Test
    void execute_shouldRefuseWhenNotEligible() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID).setEligible(false).setReason("不符合政策"));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(lockedOrder("PENDING", "199.99", USER_ID));

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

    @Test
    void rejectsNewRefundWhenExpectedCatalogFingerprintChanged() {
        givenReviewedEligible("RECEIVED", "199.99", "RECEIVED", "199.99");
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executeReviewed(differentFingerprint(currentFingerprint()),
                        "SEVEN_DAY_NO_REASON"));

        assertEquals(50005, exception.getStatus().getCode());
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
        verify(refundMapper).selectByOrderIdForUpdate(ORDER_ID);
    }

    @Test
    void rejectsNewRefundWhenReviewedPolicyCodeChanged() {
        givenReviewedEligible("SHIPPED", "199.99", "SHIPPED", "199.99");
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executeReviewed(currentFingerprint(), "QUALITY_ISSUE"));

        assertEquals(50005, exception.getStatus().getCode());
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void returnsExistingRefundDespiteOldFingerprint() {
        givenReviewedEligible("RECEIVED", "199.99", "RECEIVED", "199.99");
        when(refundMapper.selectOne(any())).thenReturn(new Refund()
                .setId(1L).setOrderId(ORDER_ID).setUserId(USER_ID)
                .setAmount(new BigDecimal("199.99")).setStatus("PENDING"));

        RefundEligibilityVO result = executeReviewed(
                differentFingerprint(currentFingerprint()), "SEVEN_DAY_NO_REASON");

        assertTrue(result.isRefundExists());
        assertEquals("该订单已有退款申请在处理中", result.getReason());
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
        verify(refundMapper, never()).selectByOrderIdForUpdate(ORDER_ID);
    }

    @Test
    void staleReadViewStillFindsConcurrentPendingWinner() {
        givenReviewedEligible("RECEIVED", "199.99", "RECEIVED", "199.99");
        Refund pendingWinner = new Refund().setId(1L).setOrderId(ORDER_ID)
                .setUserId(USER_ID).setAmount(new BigDecimal("199.99")).setStatus("PENDING");
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(pendingWinner);

        RefundEligibilityVO result = executeReviewed(
                differentFingerprint(currentFingerprint()), "SEVEN_DAY_NO_REASON");

        assertEquals("该订单已有退款申请在处理中", result.getReason());
        assertTrue(result.isRefundExists());
        verify(refundMapper).selectByOrderIdForUpdate(ORDER_ID);
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void successfulNewWriteSkipsAbsentRowLockingRead() {
        givenReviewedEligible("RECEIVED", "199.99", "RECEIVED", "199.990");
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO result = executeReviewed(currentFingerprint(), "SEVEN_DAY_NO_REASON");

        assertTrue(result.isEligible());
        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundMapper).insert(refundCaptor.capture());
        assertEquals(0, refundCaptor.getValue().getAmount().compareTo(new BigDecimal("199.99")));
        verify(refundMapper, never()).selectByOrderIdForUpdate(ORDER_ID);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals("REFUNDED", orderCaptor.getValue().getStatus());
    }

    @Test
    void lockedOrderChangeBlocksNewWrite() {
        // SHIPPED and DELIVERED resolve to the same policy, but the reviewed order status changed.
        givenReviewedEligible("SHIPPED", "199.99", "DELIVERED", "199.99");
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executeReviewed(currentFingerprint(), "SHIPPED_NOT_RECEIVED"));

        assertEquals(50005, exception.getStatus().getCode());
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void lockedOrderBecomesIneligibleAfterReviewReturnsStaleCode() {
        givenReviewedEligible("RECEIVED", "199.99", "PENDING", "199.99");
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executeReviewed(currentFingerprint(), "SEVEN_DAY_NO_REASON"));

        assertEquals(ResultStatus.REFUND_REVIEW_STALE, exception.getStatus());
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void lockedAmountChangeBlocksNewWrite() {
        givenReviewedEligible("RECEIVED", "199.99", "RECEIVED", "198.99");
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executeReviewed(currentFingerprint(), "SEVEN_DAY_NO_REASON"));

        assertEquals(50005, exception.getStatus().getCode());
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void lockedOwnerCheckedBeforeIdempotentReceipt() {
        givenEligible();
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(lockedOrder("RECEIVED", "199.99", 2002L));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.execute(ORDER_ID, "重复请求"));

        assertEquals(ResultStatus.ORDER_NOT_EXIST, exception.getStatus());
        verify(refundMapper, never()).insert(any(Refund.class));
        verify(orderMapper, never()).updateById(any(Order.class));
    }
}
