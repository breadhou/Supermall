package com.mall.module.product.controller;

import com.mall.common.result.Result;
import com.mall.module.product.entity.vo.CategoryVO;
import com.mall.module.product.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @GetMapping("/categories")
    public Result<List<CategoryVO>> getCategories() {
        List<CategoryVO> tree = categoryService.getCategoryTree();
        Result<List<CategoryVO>> result = Result.build();
        result.success(tree);
        return result;
    }
}
