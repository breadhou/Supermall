package com.mall.module.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.cart.entity.dto.AddCartItemDTO;
import com.mall.module.cart.entity.dto.UpdateCartItemDTO;
import com.mall.module.cart.entity.po.CartItem;
import com.mall.module.cart.mapper.CartItemMapper;
import com.mall.module.product.entity.po.Product;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.mapper.ProductMapper;
import com.mall.module.product.mapper.ProductSkuMapper;
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
class CartServiceImplTest {

    @Mock
    private CartItemMapper cartItemMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private CartServiceImpl cartService;

    private MockedStatic<UserContext> userContextMock;
    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;

    private static final Long USER_ID = 1001L;
    private static final Long SKU_ID = 2001L;
    private static final Long PRODUCT_ID = 3001L;

    private ProductSku mockSku;
    private Product mockProduct;

    @BeforeEach
    void setUp() {
        userContextMock = mockStatic(UserContext.class);
        snowflakeIdUtilMock = mockStatic(SnowflakeIdUtil.class);

        userContextMock.when(UserContext::getUserId).thenReturn(USER_ID);
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(9001L);

        mockSku = new ProductSku()
                .setId(SKU_ID)
                .setProductId(PRODUCT_ID)
                .setSpecs("128GB/黑色")
                .setPrice(new BigDecimal("6999.00"))
                .setStock(100)
                .setImage("https://img.example.com/sku1.jpg");

        mockProduct = new Product()
                .setId(PRODUCT_ID)
                .setName("测试手机");
    }

    @AfterEach
    void tearDown() {
        if (userContextMock != null) userContextMock.close();
        if (snowflakeIdUtilMock != null) snowflakeIdUtilMock.close();
    }

    // ==================== 加购成功 ====================

    @Test
    void addItem_shouldReturnVO_whenSuccess() {
        AddCartItemDTO dto = new AddCartItemDTO();
        dto.setSkuId(SKU_ID);
        dto.setQuantity(2);

        when(productSkuMapper.selectById(SKU_ID)).thenReturn(mockSku);
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        CartItem savedItem = new CartItem()
                .setId(9001L)
                .setUserId(USER_ID)
                .setSkuId(SKU_ID)
                .setQuantity(2);

        doAnswer(inv -> {
            CartItem item = inv.getArgument(0);
            item.setId(9001L);
            return 1;
        }).when(cartItemMapper).insert(any(CartItem.class));

        var result = cartService.addItem(dto);

        assertNotNull(result);
        assertEquals(9001L, result.getId());
        assertEquals(SKU_ID, result.getSkuId());
        assertEquals(2, result.getQuantity());
        assertEquals("128GB/黑色", result.getSpecs());
        assertEquals(new BigDecimal("6999.00"), result.getPrice());

        verify(cartItemMapper).insert(any(CartItem.class));
    }

    // ==================== 同 SKU 累加 ====================

    @Test
    void addItem_shouldAccumulateQuantity_whenSkuAlreadyInCart() {
        AddCartItemDTO dto = new AddCartItemDTO();
        dto.setSkuId(SKU_ID);
        dto.setQuantity(3);

        CartItem existing = new CartItem()
                .setId(8001L)
                .setUserId(USER_ID)
                .setSkuId(SKU_ID)
                .setQuantity(2);

        when(productSkuMapper.selectById(SKU_ID)).thenReturn(mockSku);
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        var result = cartService.addItem(dto);

        assertEquals(5, result.getQuantity());
        verify(cartItemMapper).updateById(existing);
        verify(cartItemMapper, never()).insert(any(CartItem.class));
    }

    // ==================== SKU 不存在 ====================

    @Test
    void addItem_shouldThrowException_whenSkuNotFound() {
        AddCartItemDTO dto = new AddCartItemDTO();
        dto.setSkuId(9999L);
        dto.setQuantity(1);

        when(productSkuMapper.selectById(9999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.addItem(dto));

        assertEquals(ResultStatus.DATA_NOT_FOUND, ex.getStatus());
        verify(cartItemMapper, never()).insert(any(CartItem.class));
    }

    // ==================== 列表查询 ====================

    @Test
    void listItems_shouldReturnEmptyList_whenCartEmpty() {
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = cartService.listItems();

        assertTrue(result.isEmpty());
        verify(productSkuMapper, never()).selectBatchIds(anyList());
    }

    @Test
    void listItems_shouldReturnVOList_whenCartNotEmpty() {
        CartItem item1 = new CartItem()
                .setId(8001L).setUserId(USER_ID).setSkuId(SKU_ID).setQuantity(2);

        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item1));
        when(productSkuMapper.selectBatchIds(List.of(SKU_ID))).thenReturn(List.of(mockSku));
        when(productMapper.selectBatchIds(List.of(PRODUCT_ID))).thenReturn(List.of(mockProduct));

        var result = cartService.listItems();

        assertEquals(1, result.size());
        var vo = result.get(0);
        assertEquals(8001L, vo.getId());
        assertEquals(SKU_ID, vo.getSkuId());
        assertEquals(2, vo.getQuantity());
        assertEquals("128GB/黑色", vo.getSpecs());
        assertEquals("测试手机", vo.getProductName());
    }

    // ==================== 修改数量 ====================

    @Test
    void updateQuantity_shouldReturnUpdatedVO_whenSuccess() {
        CartItem item = new CartItem()
                .setId(8001L).setUserId(USER_ID).setSkuId(SKU_ID).setQuantity(1);

        UpdateCartItemDTO dto = new UpdateCartItemDTO();
        dto.setQuantity(5);

        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);
        when(productSkuMapper.selectById(SKU_ID)).thenReturn(mockSku);

        var result = cartService.updateQuantity(8001L, dto);

        assertEquals(5, result.getQuantity());
        assertEquals(5, item.getQuantity());
        verify(cartItemMapper).updateById(item);
    }

    @Test
    void updateQuantity_shouldThrowException_whenItemNotOwn() {
        UpdateCartItemDTO dto = new UpdateCartItemDTO();
        dto.setQuantity(3);

        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(9999L, dto));

        assertEquals(ResultStatus.DATA_NOT_FOUND, ex.getStatus());
        verify(cartItemMapper, never()).updateById(any(CartItem.class));
    }

    // ==================== 删除单项 ====================

    @Test
    void deleteItem_shouldSucceed_whenItemOwned() {
        CartItem item = new CartItem()
                .setId(8001L).setUserId(USER_ID).setSkuId(SKU_ID).setQuantity(1);

        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);

        assertDoesNotThrow(() -> cartService.deleteItem(8001L));
        verify(cartItemMapper).deleteById(8001L);
    }

    @Test
    void deleteItem_shouldThrowException_whenItemNotOwn() {
        when(cartItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.deleteItem(9999L));

        assertEquals(ResultStatus.DATA_NOT_FOUND, ex.getStatus());
        verify(cartItemMapper, never()).deleteById(anyLong());
    }

    // ==================== 清空购物车 ====================

    @Test
    void clearCart_shouldRemoveAllItems() {
        assertDoesNotThrow(() -> cartService.clearCart());
        verify(cartItemMapper).delete(any(LambdaQueryWrapper.class));
    }
}
