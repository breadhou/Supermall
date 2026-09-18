package com.mall.module.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.merchant.entity.dto.MerchantProductDTO;
import com.mall.module.merchant.entity.vo.MerchantProductVO;
import com.mall.module.merchant.service.MerchantProductService;
import com.mall.module.product.entity.po.Product;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.mapper.ProductMapper;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.security.utils.MerchantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MerchantProductServiceImpl implements MerchantProductService {

    private static final String ON_SHELF = "ON_SHELF";
    private static final String OFF_SHELF = "OFF_SHELF";
    private static final Set<String> VALID_STATUS = Set.of("DRAFT", ON_SHELF, OFF_SHELF);

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;

    public MerchantProductServiceImpl(ProductMapper productMapper, ProductSkuMapper productSkuMapper) {
        this.productMapper = productMapper;
        this.productSkuMapper = productSkuMapper;
    }

    @Override
    @Transactional
    public MerchantProductVO create(MerchantProductDTO dto) {
        Long merchantId = currentMerchantId();
        LocalDateTime now = LocalDateTime.now();

        Product product = new Product()
                .setId(SnowflakeIdUtil.nextId())
                // 归属强制取自上下文，不接受请求参数传入
                .setMerchantId(merchantId)
                .setName(dto.getName())
                .setDescription(dto.getDescription())
                .setCategoryId(dto.getCategoryId())
                .setStatus(resolveStatus(dto.getStatus(), ON_SHELF))
                .setCreatedAt(now);
        productMapper.insert(product);

        for (MerchantProductDTO.SkuDTO sku : dto.getSkus()) {
            productSkuMapper.insert(new ProductSku()
                    .setId(SnowflakeIdUtil.nextId())
                    .setProductId(product.getId())
                    .setSpecs(sku.getSpecs())
                    .setPrice(sku.getPrice())
                    .setStock(sku.getStock())
                    .setImage(sku.getImage())
                    .setCreatedAt(now));
        }

        return toVO(product);
    }

    @Override
    @Transactional
    public MerchantProductVO update(Long productId, MerchantProductDTO dto) {
        Product product = requireOwnedProduct(productId);

        product.setName(dto.getName())
                .setDescription(dto.getDescription())
                .setCategoryId(dto.getCategoryId());
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            product.setStatus(resolveStatus(dto.getStatus(), product.getStatus()));
        }
        productMapper.updateById(product);

        replaceSkus(productId, dto.getSkus());
        return toVO(product);
    }

    @Override
    public void offShelf(Long productId) {
        Product product = requireOwnedProduct(productId);
        product.setStatus(OFF_SHELF);
        productMapper.updateById(product);
    }

    /**
     * SKU 全量覆盖：带 id 的更新、不带的新增、请求未提及的删除。
     *
     * <p>更新前必须校验该 SKU 确实属于本商品——否则传入别家 SKU 的 id 就能改到别人的数据。</p>
     */
    private void replaceSkus(Long productId, List<MerchantProductDTO.SkuDTO> skus) {
        Set<Long> keepIds = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();

        for (MerchantProductDTO.SkuDTO sku : skus) {
            if (sku.getId() == null) {
                productSkuMapper.insert(new ProductSku()
                        .setId(SnowflakeIdUtil.nextId())
                        .setProductId(productId)
                        .setSpecs(sku.getSpecs())
                        .setPrice(sku.getPrice())
                        .setStock(sku.getStock())
                        .setImage(sku.getImage())
                        .setCreatedAt(now));
                continue;
            }

            ProductSku existing = productSkuMapper.selectById(sku.getId());
            if (existing == null || !productId.equals(existing.getProductId())) {
                throw new BusinessException(ResultStatus.MERCHANT_PRODUCT_FORBIDDEN);
            }
            existing.setSpecs(sku.getSpecs())
                    .setPrice(sku.getPrice())
                    .setStock(sku.getStock())
                    .setImage(sku.getImage());
            productSkuMapper.updateById(existing);
            keepIds.add(existing.getId());
        }

        List<ProductSku> owned = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, productId));
        for (ProductSku sku : owned) {
            if (!keepIds.contains(sku.getId())) {
                productSkuMapper.deleteById(sku.getId());
            }
        }
    }

    /** 归属隔离：不存在与不属于本店返回同一个错误码。 */
    private Product requireOwnedProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !currentMerchantId().equals(product.getMerchantId())) {
            throw new BusinessException(ResultStatus.MERCHANT_PRODUCT_FORBIDDEN);
        }
        return product;
    }

    private Long currentMerchantId() {
        Long merchantId = MerchantContext.getMerchantId();
        if (merchantId == null) {
            throw new BusinessException(ResultStatus.MERCHANT_PRODUCT_FORBIDDEN);
        }
        return merchantId;
    }

    private String resolveStatus(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        String normalized = requested.toUpperCase();
        if (!VALID_STATUS.contains(normalized)) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }
        return normalized;
    }

    private MerchantProductVO toVO(Product product) {
        List<ProductSku> skus = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, product.getId()));
        List<MerchantProductVO.SkuVO> skuVOs = new ArrayList<>(skus.size());
        for (ProductSku sku : skus) {
            skuVOs.add(new MerchantProductVO.SkuVO()
                    .setId(sku.getId())
                    .setSpecs(sku.getSpecs())
                    .setPrice(sku.getPrice())
                    .setStock(sku.getStock())
                    .setImage(sku.getImage()));
        }
        return new MerchantProductVO()
                .setId(product.getId())
                .setMerchantId(product.getMerchantId())
                .setName(product.getName())
                .setDescription(product.getDescription())
                .setCategoryId(product.getCategoryId())
                .setStatus(product.getStatus())
                .setCreatedAt(product.getCreatedAt())
                .setSkus(skuVOs);
    }
}
