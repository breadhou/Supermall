package com.mall.module.merchant.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mall.common.result.Result;
import com.mall.module.logistics.entity.dto.ShipOrderDTO;
import com.mall.module.logistics.entity.vo.LogisticsVO;
import com.mall.module.merchant.entity.dto.MerchantOrderPageDTO;
import com.mall.module.merchant.entity.vo.MerchantOrderVO;
import com.mall.module.merchant.service.MerchantOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/orders")
public class MerchantOrderController {

    private final MerchantOrderService merchantOrderService;

    public MerchantOrderController(MerchantOrderService merchantOrderService) {
        this.merchantOrderService = merchantOrderService;
    }

    @GetMapping
    public Result<IPage<MerchantOrderVO>> listOrders(@Valid MerchantOrderPageDTO dto) {
        Result<IPage<MerchantOrderVO>> result = Result.build();
        result.success(merchantOrderService.listOrders(dto));
        return result;
    }

    @PostMapping("/{orderNo}/ship")
    public Result<LogisticsVO> ship(@PathVariable String orderNo,
                                    @Valid @RequestBody ShipOrderDTO dto) {
        Result<LogisticsVO> result = Result.build();
        result.success(merchantOrderService.ship(orderNo, dto));
        return result;
    }

    @PostMapping("/{orderNo}/deliver")
    public Result<LogisticsVO> deliver(@PathVariable String orderNo) {
        Result<LogisticsVO> result = Result.build();
        result.success(merchantOrderService.deliver(orderNo));
        return result;
    }
}
