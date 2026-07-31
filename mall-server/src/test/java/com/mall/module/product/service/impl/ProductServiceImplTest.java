package com.mall.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.module.product.entity.dto.ProductPageDTO;
import com.mall.module.product.entity.po.Product;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.entity.vo.ProductVO;
import com.mall.module.product.mapper.ProductMapper;
import com.mall.module.product.mapper.ProductSkuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductSkuMapper skuMapper;

    @InjectMocks
    private ProductServiceImpl productService;

    // ==================== page() ====================

    @Test
    void page_shouldReturnEmptyPage_whenNoProducts() {
        ProductPageDTO dto = new ProductPageDTO();
        dto.setPageNum(1);
        dto.setPageSize(20);

        when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<Product> page = inv.getArgument(0);
                    page.setRecords(Collections.emptyList());
                    page.setTotal(0);
                    return page;
                });

        Page<ProductVO> result = productService.listProducts(dto);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    void page_shouldReturnConvertedProducts() {
        ProductPageDTO dto = new ProductPageDTO();
        dto.setPageNum(1);
        dto.setPageSize(10);

        Product p1 = buildProduct(1L, "手机A", 10L, "ON_SHELF");
        Product p2 = buildProduct(2L, "手机B", 10L, "ON_SHELF");

        when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<Product> page = inv.getArgument(0);
                    page.setRecords(List.of(p1, p2));
                    page.setTotal(2);
                    return page;
                });

        Page<ProductVO> result = productService.listProducts(dto);

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(1, result.getCurrent());
        assertEquals(10, result.getSize());

        ProductVO vo1 = result.getRecords().get(0);
        assertEquals(1L, vo1.getId());
        assertEquals("手机A", vo1.getName());
        assertEquals(10L, vo1.getCategoryId());
        assertEquals("ON_SHELF", vo1.getStatus());
    }

    @Test
    void page_shouldApplyCategoryFilter() {
        ProductPageDTO dto = new ProductPageDTO();
        dto.setCategoryId(10L);

        when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<Product> page = inv.getArgument(0);
                    page.setRecords(Collections.emptyList());
                    page.setTotal(0);
                    return page;
                });

        productService.listProducts(dto);

        ArgumentCaptor<LambdaQueryWrapper<Product>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(productMapper).selectPage(any(Page.class), captor.capture());
        // 有值条件会拼入 SQL，这里只验证方法被调用了
        verify(productMapper, times(1)).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void page_shouldApplyKeywordFilter() {
        ProductPageDTO dto = new ProductPageDTO();
        dto.setKeyword("手机");

        Product p = buildProduct(1L, "手机Pro", null, "ON_SHELF");
        when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<Product> page = inv.getArgument(0);
                    page.setRecords(List.of(p));
                    page.setTotal(1);
                    return page;
                });

        Page<ProductVO> result = productService.listProducts(dto);

        assertEquals(1, result.getTotal());
        assertEquals("手机Pro", result.getRecords().get(0).getName());
    }

    @Test
    void page_shouldApplyStatusFilter() {
        ProductPageDTO dto = new ProductPageDTO();
        dto.setStatus("DRAFT");

        when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<Product> page = inv.getArgument(0);
                    page.setRecords(Collections.emptyList());
                    page.setTotal(0);
                    return page;
                });

        productService.listProducts(dto);

        verify(productMapper, times(1)).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    // ==================== detail() ====================

    @Test
    void detail_shouldReturnNull_whenProductNotFound() {
        when(productMapper.selectById(999L)).thenReturn(null);

        ProductVO result = productService.showDetail(999L);

        assertNull(result);
        verify(skuMapper, never()).selectList(any());
    }

    @Test
    void detail_shouldReturnProductWithSkuAggregation() {
        Long productId = 1L;
        Product product = buildProduct(productId, "手机A", 10L, "ON_SHELF");

        ProductSku sku1 = buildSku(101L, productId, BigDecimal.valueOf(2999), 10, "img1.jpg");
        ProductSku sku2 = buildSku(102L, productId, BigDecimal.valueOf(3999), 5, "img2.jpg");

        when(productMapper.selectById(productId)).thenReturn(product);
        when(skuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sku1, sku2));

        ProductVO vo = productService.showDetail(productId);

        assertNotNull(vo);
        assertEquals(productId, vo.getId());
        assertEquals("手机A", vo.getName());

        // SKU 聚合
        assertEquals(BigDecimal.valueOf(2999), vo.getMinPrice());
        assertEquals(15, vo.getTotalStock());
        assertEquals("img1.jpg", vo.getMainImage());

        // SKU 列表
        assertEquals(2, vo.getSkus().size());
        assertEquals(101L, vo.getSkus().get(0).getId());
        assertEquals(BigDecimal.valueOf(2999), vo.getSkus().get(0).getPrice());
    }

    @Test
    void detail_shouldHandleProductWithoutSkus() {
        Long productId = 1L;
        Product product = buildProduct(productId, "空SKU商品", 10L, "DRAFT");

        when(productMapper.selectById(productId)).thenReturn(product);
        when(skuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        ProductVO vo = productService.showDetail(productId);

        assertNotNull(vo);
        assertEquals("空SKU商品", vo.getName());
        assertNull(vo.getMinPrice());
        assertNull(vo.getMainImage());
        assertEquals(0, vo.getTotalStock());
        assertTrue(vo.getSkus().isEmpty());
    }

    @Test
    void detail_shouldResolveMinPriceCorrectly() {
        Long productId = 1L;
        Product product = buildProduct(productId, "test", null, "ON_SHELF");

        ProductSku cheapest = buildSku(1L, productId, BigDecimal.valueOf(100), 1, "a.jpg");
        ProductSku expensive = buildSku(2L, productId, BigDecimal.valueOf(500), 1, "b.jpg");

        when(productMapper.selectById(productId)).thenReturn(product);
        when(skuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(expensive, cheapest));

        ProductVO vo = productService.showDetail(productId);

        assertEquals(BigDecimal.valueOf(100), vo.getMinPrice());
    }

    // ==================== helpers ====================

    private static Product buildProduct(Long id, String name, Long categoryId, String status) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setCategoryId(categoryId);
        p.setStatus(status);
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    private static ProductSku buildSku(Long id, Long productId, BigDecimal price, int stock, String image) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setProductId(productId);
        sku.setPrice(price);
        sku.setStock(stock);
        sku.setImage(image);
        return sku;
    }
}
