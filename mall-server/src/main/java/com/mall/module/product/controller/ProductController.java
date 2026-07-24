package com.mall.module.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.Result;
import com.mall.module.product.entity.dto.ProductPageDTO;
import com.mall.module.product.entity.vo.ProductVO;
import com.mall.module.product.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductService productService;

    @GetMapping
    public Result<Page<ProductVO>> listProducts(ProductPageDTO dto) {
        Page<ProductVO> page = productService.page(dto);
        Result<Page<ProductVO>> result = Result.build();
        result.success(page);
        return result;
    }

    @GetMapping("/{id}")
    public Result<ProductVO> showDetails(@PathVariable Long id) {
        ProductVO vo = productService.detail(id);
        Result<ProductVO> result = Result.build();
        result.success(vo);
        return result;
    }
}
