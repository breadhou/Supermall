package com.mall.module.product.controller;

import com.mall.common.result.Result;
import com.mall.module.product.entity.vo.ProductSkuVO;
import com.mall.module.product.service.SkuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skus")
public class SkuController {

    @Autowired
    private SkuService skuService;

    @GetMapping("/{id}")
    public Result<ProductSkuVO> getById(@PathVariable Long id) {
        ProductSkuVO vo = skuService.getById(id);
        Result<ProductSkuVO> result = Result.build();
        result.success(vo);
        return result;
    }
}
