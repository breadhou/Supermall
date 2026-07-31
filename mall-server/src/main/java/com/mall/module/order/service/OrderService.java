package com.mall.module.order.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.module.order.entity.dto.CreateOrderDTO;
import com.mall.module.order.entity.dto.OrderPageDTO;
import com.mall.module.order.entity.dto.RefundDTO;

import com.mall.module.order.entity.vo.OrderListVO;
import com.mall.module.order.entity.vo.OrderVO;

public interface OrderService {

    OrderVO createOrder(CreateOrderDTO dto);

    Page<OrderListVO> listOrders(OrderPageDTO dto);

    OrderVO getOrderDetail(Long orderId);

    void cancelOrder(Long orderId);

    void confirmReceipt(Long orderId);

    void requestRefund(Long orderId, RefundDTO dto);

}
