package com.mall.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.coupon.entity.vo.CouponApplyResult;
import com.mall.module.coupon.service.CouponService;
import com.mall.module.order.entity.dto.CreateOrderDTO;
import com.mall.module.order.entity.dto.OrderPageDTO;
import com.mall.module.order.entity.dto.RefundDTO;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.OrderItem;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.mapper.OrderItemMapper;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.mapper.AddressMapper;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private RefundMapper refundMapper;

    @Mock
    private AddressMapper addressMapper;

    @Mock
    private ProductSkuMapper skuMapper;

    @Mock
    private CouponService couponService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private MockedStatic<UserContext> userContextMock;
    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;

    private static final Long USER_ID = 1001L;
    private static final Long ADDRESS_ID = 2001L;
    private static final Long ORDER_ID = 9001L;
    private static final Long SKU_ID = 3001L;

    private Address mockAddress;
    private ProductSku mockSku;
    private Order mockOrder;
    private CreateOrderDTO createOrderDTO;

    @BeforeEach
    void setUp() {
        userContextMock = mockStatic(UserContext.class);
        snowflakeIdUtilMock = mockStatic(SnowflakeIdUtil.class);

        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);
        // 每次 nextId() 返回递增的 ID：9001, 9002, 9003...
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(ORDER_ID, 9100L, 9101L);

        mockAddress = new Address()
                .setId(ADDRESS_ID)
                .setUserId(USER_ID);

        mockSku = new ProductSku()
                .setId(SKU_ID)
                .setProductId(4001L)
                .setSpecs("128GB")
                .setPrice(new BigDecimal("2999.00"))
                .setStock(50)
                .setImage("img.jpg");

        CreateOrderDTO.OrderItemDTO itemDTO = new CreateOrderDTO.OrderItemDTO();
        itemDTO.setSkuId(SKU_ID);
        itemDTO.setQuantity(2);

        createOrderDTO = new CreateOrderDTO();
        createOrderDTO.setAddressId(ADDRESS_ID);
        createOrderDTO.setItems(List.of(itemDTO));

        mockOrder = new Order()
                .setId(ORDER_ID)
                .setOrderNo("9001")
                .setUserId(USER_ID)
                .setAddressId(ADDRESS_ID)
                .setTotalAmount(new BigDecimal("5998.00"))
                .setStatus("PENDING");
    }

    @AfterEach
    void tearDown() {
        if (userContextMock != null) userContextMock.close();
        if (snowflakeIdUtilMock != null) snowflakeIdUtilMock.close();
    }

    // ==================== 创建订单 ====================

    @Test
    void createOrder_shouldReturnOrderVO_whenSuccess() {
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);
        when(skuMapper.selectById(SKU_ID)).thenReturn(mockSku);

        var result = orderService.createOrder(createOrderDTO);

        assertNotNull(result);
        assertEquals("9001", result.getOrderNo());
        assertEquals("PENDING", result.getStatus());
        assertEquals(0, new BigDecimal("5998.00").compareTo(result.getTotalAmount()));
        assertEquals(1, result.getItems().size());
        assertEquals(SKU_ID, result.getItems().get(0).getSkuId());
        assertEquals("128GB", result.getItems().get(0).getSpecs());

        verify(orderMapper).insert(any(Order.class));
        verify(orderItemMapper).insert(any(OrderItem.class));
    }

    @Test
    void createOrder_shouldUseCouponAndPersistDiscountedAmount() {
        Long couponId = 7001L;
        createOrderDTO.setCouponId(couponId);
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);
        when(skuMapper.selectById(SKU_ID)).thenReturn(mockSku);
        when(couponService.useCoupon(couponId, new BigDecimal("5998.00")))
                .thenReturn(new CouponApplyResult(
                        new BigDecimal("5998.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("5988.00")
                ));

        var result = orderService.createOrder(createOrderDTO);

        assertEquals(new BigDecimal("5988.00"), result.getTotalAmount());
        assertEquals(couponId, result.getCouponId());
        verify(couponService).useCoupon(couponId, new BigDecimal("5998.00"));
    }

    @Test
    void createOrder_shouldThrowException_whenAddressNotBelongToUser() {
        mockAddress.setUserId(9999L); // 地址不属于当前用户
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(createOrderDTO));

        assertEquals(ResultStatus.DATA_NOT_FOUND, ex.getStatus());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrder_shouldThrowException_whenSkuNotFound() {
        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);
        when(skuMapper.selectById(SKU_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(createOrderDTO));

        assertEquals(ResultStatus.DATA_NOT_FOUND, ex.getStatus());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void createOrder_shouldCalculateTotalAmountWithQuantity() {
        CreateOrderDTO.OrderItemDTO item1 = new CreateOrderDTO.OrderItemDTO();
        item1.setSkuId(3001L);
        item1.setQuantity(2);

        ProductSku sku1 = new ProductSku().setId(3001L).setPrice(new BigDecimal("100.00")).setProductId(4001L);
        ProductSku sku2 = new ProductSku().setId(3002L).setPrice(new BigDecimal("200.00")).setProductId(4002L);

        CreateOrderDTO.OrderItemDTO item2 = new CreateOrderDTO.OrderItemDTO();
        item2.setSkuId(3002L);
        item2.setQuantity(3);

        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setAddressId(ADDRESS_ID);
        dto.setItems(List.of(item1, item2));

        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);
        when(skuMapper.selectById(3001L)).thenReturn(sku1);
        when(skuMapper.selectById(3002L)).thenReturn(sku2);

        var result = orderService.createOrder(dto);

        // 100*2 + 200*3 = 200 + 600 = 800
        assertEquals(0, new BigDecimal("800.00").compareTo(result.getTotalAmount()));
    }

    // ==================== 订单列表 ====================

    @Test
    void listOrders_shouldReturnPagedResult_whenHasOrders() {
        OrderPageDTO dto = new OrderPageDTO();
        dto.setPageNum(1);
        dto.setPageSize(10);

        Order order = new Order().setId(ORDER_ID).setUserId(USER_ID)
                .setOrderNo("9001").setTotalAmount(new BigDecimal("100.00"))
                .setStatus("PENDING");

        Page<Order> mockPage = new Page<>(1, 10);
        mockPage.setTotal(1);
        mockPage.setRecords(List.of(order));

        when(orderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(new OrderItem().setOrderId(ORDER_ID)));

        var result = orderService.listOrders(dto);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(1, result.getRecords().get(0).getItemCount());
    }

    @Test
    void listOrders_shouldReturnEmptyPage_whenNoOrders() {
        OrderPageDTO dto = new OrderPageDTO();

        Page<Order> mockPage = new Page<>(1, 20);
        mockPage.setTotal(0);
        mockPage.setRecords(List.of());

        when(orderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

        var result = orderService.listOrders(dto);

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    // ==================== 订单详情 ====================

    @Test
    void getOrderDetail_shouldReturnOrderWithItems_whenSuccess() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        OrderItem item = new OrderItem().setId(9100L).setOrderId(ORDER_ID)
                .setSkuId(SKU_ID).setPrice(new BigDecimal("2999.00")).setQuantity(2);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item));
        when(skuMapper.selectByIds(List.of(SKU_ID))).thenReturn(List.of(mockSku));

        var result = orderService.getOrderDetail(ORDER_ID);

        assertNotNull(result);
        assertEquals("9001", result.getOrderNo());
        assertEquals(1, result.getItems().size());
        assertEquals(SKU_ID, result.getItems().get(0).getSkuId());
        assertEquals("128GB", result.getItems().get(0).getSpecs());
    }

    @Test
    void getOrderDetail_shouldThrowException_whenOrderNotExist() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.getOrderDetail(ORDER_ID));

        assertEquals(ResultStatus.ORDER_NOT_EXIST, ex.getStatus());
    }

    @Test
    void getOrderDetail_shouldThrowException_whenOrderNotBelongToUser() {
        mockOrder.setUserId(9999L);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.getOrderDetail(ORDER_ID));

        assertEquals(ResultStatus.ORDER_NOT_EXIST, ex.getStatus());
    }

    // ==================== 取消订单 ====================

    @Test
    void cancelOrder_shouldUpdateToCancelled_whenStatusIsPending() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        assertDoesNotThrow(() -> orderService.cancelOrder(ORDER_ID));

        assertEquals("CANCELLED", mockOrder.getStatus());
        verify(orderMapper).updateById(mockOrder);
    }

    @Test
    void cancelOrder_shouldRestoreCoupon_whenOrderUsedCoupon() {
        Long couponId = 7001L;
        mockOrder.setCouponId(couponId);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        orderService.cancelOrder(ORDER_ID);

        verify(couponService).restoreCoupon(couponId);
        verify(orderMapper).updateById(mockOrder);
    }

    @Test
    void cancelOrder_shouldThrowException_whenStatusNotPending() {
        mockOrder.setStatus("PAID");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancelOrder(ORDER_ID));

        assertEquals(ResultStatus.ORDER_STATUS_ERROR, ex.getStatus());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void cancelOrder_shouldThrowException_whenOrderNotExist() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancelOrder(ORDER_ID));

        assertEquals(ResultStatus.ORDER_NOT_EXIST, ex.getStatus());
    }

    // ==================== 确认收货 ====================

    @Test
    void confirmReceipt_shouldUpdateToReceived_whenStatusIsShipped() {
        mockOrder.setStatus("SHIPPED");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        assertDoesNotThrow(() -> orderService.confirmReceipt(ORDER_ID));

        assertEquals("RECEIVED", mockOrder.getStatus());
        verify(orderMapper).updateById(mockOrder);
    }

    @Test
    void confirmReceipt_shouldThrowException_whenStatusNotShipped() {
        mockOrder.setStatus("PAID");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.confirmReceipt(ORDER_ID));

        assertEquals(ResultStatus.ORDER_STATUS_ERROR, ex.getStatus());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    // ==================== 申请退款 ====================

    @Test
    void requestRefund_shouldCreateRefund_whenStatusIsPaid() {
        mockOrder.setStatus("PAID");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        RefundDTO dto = new RefundDTO();
        dto.setReason("质量问题");

        assertDoesNotThrow(() -> orderService.requestRefund(ORDER_ID, dto));

        verify(refundMapper).insert(any(Refund.class));
    }

    @Test
    void requestRefund_shouldCreateRefund_whenStatusIsReceived() {
        mockOrder.setStatus("RECEIVED");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        RefundDTO dto = new RefundDTO();
        dto.setReason("不想要了");

        assertDoesNotThrow(() -> orderService.requestRefund(ORDER_ID, dto));

        verify(refundMapper).insert(any(Refund.class));
    }

    @Test
    void requestRefund_shouldThrowException_whenStatusNotAllowed() {
        mockOrder.setStatus("PENDING");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(mockOrder);

        RefundDTO dto = new RefundDTO();
        dto.setReason("不想要了");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.requestRefund(ORDER_ID, dto));

        assertEquals(ResultStatus.ORDER_STATUS_ERROR, ex.getStatus());
        verify(refundMapper, never()).insert(any(Refund.class));
    }
}
