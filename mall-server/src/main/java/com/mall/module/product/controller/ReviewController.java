package com.mall.module.product.controller;

import com.mall.common.result.Result;
import com.mall.module.product.entity.vo.ReviewVO;
import com.mall.module.product.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @GetMapping("/{id}/reviews")
    public Result<List<ReviewVO>> listByProductId(@PathVariable Long id) {
        List<ReviewVO> reviews = reviewService.listByProductId(id);
        Result<List<ReviewVO>> result = Result.build();
        result.success(reviews);
        return result;
    }
}
