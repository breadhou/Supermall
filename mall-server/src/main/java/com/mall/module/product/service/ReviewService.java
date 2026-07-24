package com.mall.module.product.service;

import com.mall.module.product.entity.vo.ReviewVO;

import java.util.List;

public interface ReviewService {

    List<ReviewVO> listByProductId(Long productId);
}
