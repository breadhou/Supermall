package com.mall.module.product.service.impl;

import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.entity.vo.ProductSkuVO;
import com.mall.module.product.mapper.ProductSkuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkuServiceImplTest {

    @Mock
    private ProductSkuMapper skuMapper;

    @InjectMocks
    private SkuServiceImpl skuService;

    @Test
    void getById_shouldReturnSkuVO_whenFound() {
        ProductSku sku = new ProductSku();
        sku.setId(1L);
        sku.setProductId(10L);
        sku.setSpecs("{\"颜色\":\"黑色\"}");
        sku.setPrice(BigDecimal.valueOf(2999));
        sku.setStock(50);
        sku.setImage("sku.jpg");

        when(skuMapper.selectById(1L)).thenReturn(sku);

        ProductSkuVO vo = skuService.getById(1L);

        assertNotNull(vo);
        assertEquals(1L, vo.getId());
        assertEquals("{\"颜色\":\"黑色\"}", vo.getSpecs());
        assertEquals(BigDecimal.valueOf(2999), vo.getPrice());
        assertEquals(50, vo.getStock());
        assertEquals("sku.jpg", vo.getImage());
    }

    @Test
    void getById_shouldReturnNull_whenNotFound() {
        when(skuMapper.selectById(999L)).thenReturn(null);

        ProductSkuVO vo = skuService.getById(999L);

        assertNull(vo);
    }
}
