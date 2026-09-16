package com.mall.module.logistics.controller;

import com.mall.common.result.Result;
import com.mall.module.logistics.entity.vo.LogisticsVO;
import com.mall.module.logistics.service.LogisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class LogisticsController {

    private final LogisticsService logisticsService;

    public LogisticsController(LogisticsService logisticsService) {
        this.logisticsService = logisticsService;
    }

    @GetMapping("/{orderId}/logistics")
    public Result<LogisticsVO> getLogistics(@PathVariable Long orderId) {
        Result<LogisticsVO> result = Result.build();
        result.success(logisticsService.getLogistics(orderId));
        return result;
    }

}
