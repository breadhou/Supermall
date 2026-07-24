package com.mall.module.product.service.impl;

import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.entity.vo.ProductSkuVO;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.module.product.service.SkuService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SkuServiceImpl implements SkuService {

    @Autowired
    private ProductSkuMapper skuMapper;

    @Override
    public ProductSkuVO getById(Long id) {
        ProductSku sku = skuMapper.selectById(id);
        if (sku == null) {
            return null;
        }
        ProductSkuVO vo = new ProductSkuVO();
        BeanUtils.copyProperties(sku, vo);
        return vo;
    }
}
