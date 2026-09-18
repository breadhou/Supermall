package com.mall.module.merchant.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.merchant.entity.dto.MerchantProductDTO;
import com.mall.module.product.entity.po.Product;
import com.mall.module.product.mapper.ProductMapper;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.security.utils.MerchantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家商品服务的归属隔离。这是本模块的安全核心：做糙了就是越权漏洞。
 */
@ExtendWith(MockitoExtension.class)
class MerchantProductServiceImplTest {

    private static final Long MERCHANT_A = 5001L;
    private static final Long MERCHANT_B = 5002L;
    private static final Long PRODUCT_ID = 6001L;

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;

    private MerchantProductServiceImpl service;
    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;

    @BeforeEach
    void setUp() {
        snowflakeIdUtilMock = mockStatic(SnowflakeIdUtil.class);
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(7001L, 7002L, 7003L, 7004L);
        service = new MerchantProductServiceImpl(productMapper, productSkuMapper);
        MerchantContext.set(1001L, MERCHANT_A);
    }

    @AfterEach
    void tearDown() {
        MerchantContext.clear();
        snowflakeIdUtilMock.close();
    }

    private MerchantProductDTO dto() {
        MerchantProductDTO.SkuDTO sku = new MerchantProductDTO.SkuDTO();
        sku.setSpecs("{\"颜色\":\"黑\"}");
        sku.setPrice(new BigDecimal("19.90"));
        sku.setStock(10);

        MerchantProductDTO dto = new MerchantProductDTO();
        dto.setName("测试商品");
        dto.setCategoryId(1L);
        dto.setSkus(List.of(sku));
        return dto;
    }

    @Test
    void create_shouldForceMerchantIdFromContextIgnoringRequest() {
        service.create(dto());

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).insert(captor.capture());
        assertEquals(MERCHANT_A, captor.getValue().getMerchantId());
    }

    @Test
    void update_shouldRejectProductOwnedByAnotherMerchant() {
        when(productMapper.selectById(PRODUCT_ID))
                .thenReturn(new Product().setId(PRODUCT_ID).setMerchantId(MERCHANT_B));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.update(PRODUCT_ID, dto()));

        assertEquals(ResultStatus.MERCHANT_PRODUCT_FORBIDDEN, exception.getStatus());
        verify(productMapper, never()).updateById(any(Product.class));
    }

    @Test
    void offShelf_shouldRejectProductOwnedByAnotherMerchant() {
        when(productMapper.selectById(PRODUCT_ID))
                .thenReturn(new Product().setId(PRODUCT_ID).setMerchantId(MERCHANT_B));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.offShelf(PRODUCT_ID));

        assertEquals(ResultStatus.MERCHANT_PRODUCT_FORBIDDEN, exception.getStatus());
        verify(productMapper, never()).updateById(any(Product.class));
    }

    @Test
    void update_shouldRejectSkuIdBelongingToAnotherProduct() {
        when(productMapper.selectById(PRODUCT_ID))
                .thenReturn(new Product().setId(PRODUCT_ID).setMerchantId(MERCHANT_A));
        // 传入的 skuId 存在，但挂在别的商品下——不校验就能改到别人的 SKU
        when(productSkuMapper.selectById(9999L)).thenReturn(
                new com.mall.module.product.entity.po.ProductSku().setId(9999L).setProductId(8888L));

        MerchantProductDTO dto = dto();
        dto.getSkus().get(0).setId(9999L);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.update(PRODUCT_ID, dto));

        assertEquals(ResultStatus.MERCHANT_PRODUCT_FORBIDDEN, exception.getStatus());
    }
}
