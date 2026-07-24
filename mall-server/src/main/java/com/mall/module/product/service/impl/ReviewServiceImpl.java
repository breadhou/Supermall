package com.mall.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.module.product.entity.po.Review;
import com.mall.module.product.entity.vo.ReviewVO;
import com.mall.module.product.mapper.ReviewMapper;
import com.mall.module.product.service.ReviewService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ReviewServiceImpl implements ReviewService {

    @Autowired
    private ReviewMapper reviewMapper;

    @Override
    public List<ReviewVO> listByProductId(Long productId) {
        List<Review> reviews = reviewMapper.selectList(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getProductId, productId)
                        .orderByDesc(Review::getCreatedAt)
        );

        return reviews.stream()
                .map(review -> {
                    ReviewVO vo = new ReviewVO();
                    BeanUtils.copyProperties(review, vo);
                    return vo;
                })
                .collect(Collectors.toList());
    }
}
