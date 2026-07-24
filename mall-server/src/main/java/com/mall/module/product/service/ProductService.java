package com.mall.module.product.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.module.product.entity.dto.ProductPageDTO;
import com.mall.module.product.entity.vo.ProductVO;

public interface ProductService {

    Page<ProductVO> page(ProductPageDTO dto);

    ProductVO detail(Long id);
}
