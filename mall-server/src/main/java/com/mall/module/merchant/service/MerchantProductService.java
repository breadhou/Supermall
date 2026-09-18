package com.mall.module.merchant.service;

import com.mall.module.merchant.entity.dto.MerchantProductDTO;
import com.mall.module.merchant.entity.vo.MerchantProductVO;

public interface MerchantProductService {

    MerchantProductVO create(MerchantProductDTO dto);

    MerchantProductVO update(Long productId, MerchantProductDTO dto);

    void offShelf(Long productId);

}
