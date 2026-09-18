package com.mall.module.merchant.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mall.module.logistics.entity.dto.ShipOrderDTO;
import com.mall.module.logistics.entity.vo.LogisticsVO;
import com.mall.module.merchant.entity.dto.MerchantOrderPageDTO;
import com.mall.module.merchant.entity.vo.MerchantOrderVO;

public interface MerchantOrderService {

    IPage<MerchantOrderVO> listOrders(MerchantOrderPageDTO dto);

    LogisticsVO ship(String orderNo, ShipOrderDTO dto);

    LogisticsVO deliver(String orderNo);

}
