package com.mall.module.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.Result;
import com.mall.module.order.entity.dto.CreateOrderDTO;
import com.mall.module.order.entity.dto.OrderPageDTO;
import com.mall.module.order.entity.dto.RefundDTO;
import com.mall.module.order.entity.vo.OrderListVO;
import com.mall.module.order.entity.vo.OrderVO;
import com.mall.module.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    OrderService orderService;

    @PostMapping
    public Result<OrderVO> createOrder(@Valid @RequestBody CreateOrderDTO dto) {
        OrderVO vo = orderService.createOrder(dto);
        Result<OrderVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @GetMapping
    public Result<Page<OrderListVO>> listOrders(@Valid OrderPageDTO dto) {
        Page<OrderListVO> page = orderService.listOrders(dto);
        Result<Page<OrderListVO>> result = Result.build();
        result.success(page);
        return result;
    }

    @GetMapping("/{id}")
    public Result<OrderVO> getOrderDetail(@PathVariable Long id) {
        OrderVO vo = orderService.getOrderDetail(id);
        Result<OrderVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @PutMapping("/{id}/cancel")
    public Result<Void> cancelOrder(@PathVariable Long id) {
        orderService.cancelOrder(id);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }

    @PutMapping("/{id}/receive")
    public Result<Void> confirmReceipt(@PathVariable Long id) {
        orderService.confirmReceipt(id);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }

    @PostMapping("/{id}/refund")
    public Result<Void> requestRefund(@PathVariable Long id, @Valid @RequestBody RefundDTO dto) {
        orderService.requestRefund(id, dto);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }
}
