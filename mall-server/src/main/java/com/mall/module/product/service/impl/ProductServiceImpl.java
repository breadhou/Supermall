package com.mall.module.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.module.product.entity.dto.ProductPageDTO;
import com.mall.module.product.entity.po.Product;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.entity.vo.ProductVO;
import com.mall.module.product.entity.vo.ProductSkuVO;
import com.mall.module.product.mapper.ProductMapper;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.module.product.service.ProductService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    ProductMapper productMapper;

    @Autowired
    ProductSkuMapper skuMapper;

    @Override
    public Page<ProductVO> listProducts(ProductPageDTO dto) {
        Page<Product> page = new Page<>(dto.getPageNum(), dto.getPageSize());

        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(dto.getCategoryId() != null, Product::getCategoryId, dto.getCategoryId())
                .like(StrUtil.isNotBlank(dto.getKeyword()), Product::getName, dto.getKeyword())
                .eq(StrUtil.isNotBlank(dto.getStatus()), Product::getStatus, dto.getStatus())
                .orderByDesc(Product::getCreatedAt);

        productMapper.selectPage(page, wrapper);

        List<ProductVO> productVOList = page.getRecords().stream()
                .map(product -> {
                    ProductVO vo = new ProductVO();
                    BeanUtils.copyProperties(product, vo);
                    return vo;
                })
                .toList();

        Page<ProductVO> result = new Page<>(dto.getPageNum(), dto.getPageSize());
        result.setRecords(productVOList);
        result.setTotal(page.getTotal());
        return result;
    }

    @Override
    public ProductVO showDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            return null;
        }

        List<ProductSku> skus = skuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id)
        );

        List<ProductSkuVO> skuVOList = skus.stream()
                .map(sku -> {
                    ProductSkuVO skuVO = new ProductSkuVO();
                    BeanUtils.copyProperties(sku, skuVO);
                    return skuVO;
                })
                .collect(Collectors.toList());

        ProductVO vo = new ProductVO();
        BeanUtils.copyProperties(product, vo);
        vo.setMinPrice(skus.stream().map(ProductSku::getPrice).min(Comparator.naturalOrder()).orElse(null));
        vo.setMainImage(skus.isEmpty() ? null : skus.get(0).getImage());
        vo.setTotalStock(skus.stream().mapToInt(ProductSku::getStock).sum());
        vo.setSkus(skuVOList);
        return vo;
    }
}
