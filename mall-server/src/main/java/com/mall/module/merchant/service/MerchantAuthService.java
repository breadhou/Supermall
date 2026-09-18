package com.mall.module.merchant.service;

import com.mall.module.merchant.entity.dto.MerchantLoginDTO;
import com.mall.module.merchant.entity.vo.MerchantLoginVO;

public interface MerchantAuthService {

    MerchantLoginVO login(MerchantLoginDTO dto);

}
