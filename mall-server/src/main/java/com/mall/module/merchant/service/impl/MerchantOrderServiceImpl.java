package com.mall.module.merchant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.logistics.entity.dto.ShipOrderDTO;
import com.mall.module.logistics.entity.vo.LogisticsVO;
import com.mall.module.logistics.service.LogisticsService;
import com.mall.module.merchant.entity.dto.MerchantOrderPageDTO;
import com.mall.module.merchant.entity.vo.MerchantOrderItemVO;
import com.mall.module.merchant.entity.vo.MerchantOrderVO;
import com.mall.module.merchant.mapper.MerchantOrderMapper;
import com.mall.module.merchant.service.MerchantOrderService;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.security.utils.MerchantContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MerchantOrderServiceImpl implements MerchantOrderService {

    private final MerchantOrderMapper merchantOrderMapper;
    private final OrderMapper orderMapper;
    private final LogisticsService logisticsService;

    public MerchantOrderServiceImpl(MerchantOrderMapper merchantOrderMapper,
                                    OrderMapper orderMapper,
                                    LogisticsService logisticsService) {
        this.merchantOrderMapper = merchantOrderMapper;
        this.orderMapper = orderMapper;
        this.logisticsService = logisticsService;
    }

    @Override
    public IPage<MerchantOrderVO> listOrders(MerchantOrderPageDTO dto) {
        Long merchantId = currentMerchantId();
        Page<MerchantOrderVO> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        IPage<MerchantOrderVO> result =
                merchantOrderMapper.selectStoreOrders(page, merchantId, dto.getStatus());

        List<MerchantOrderVO> records = result.getRecords();
        if (records.isEmpty()) {
            return result;
        }

        List<Long> orderIds = new ArrayList<>(records.size());
        for (MerchantOrderVO record : records) {
            orderIds.add(record.getOrderId());
        }

        Map<Long, List<MerchantOrderItemVO>> itemsByOrder = new HashMap<>();
        for (MerchantOrderItemVO item : merchantOrderMapper.selectStoreOrderItems(orderIds, merchantId)) {
            itemsByOrder.computeIfAbsent(item.getOrderId(), key -> new ArrayList<>()).add(item);
        }
        for (MerchantOrderVO record : records) {
            record.setItems(itemsByOrder.getOrDefault(record.getOrderId(), List.of()));
        }
        return result;
    }

    @Override
    public LogisticsVO ship(String orderNo, ShipOrderDTO dto) {
        Order order = requireStoreOrder(orderNo);
        return logisticsService.shipOrderForMerchant(order.getId(), dto);
    }

    @Override
    public LogisticsVO deliver(String orderNo) {
        Order order = requireStoreOrder(orderNo);
        return logisticsService.markDeliveredForMerchant(order.getId());
    }

    /** 归属校验：订单不存在与不含本店商品返回不同码值，但都不泄漏订单内容。 */
    private Order requireStoreOrder(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }
        if (merchantOrderMapper.countStoreOrderItems(order.getId(), currentMerchantId()) == 0) {
            throw new BusinessException(ResultStatus.MERCHANT_ORDER_FORBIDDEN);
        }
        return order;
    }

    private Long currentMerchantId() {
        Long merchantId = MerchantContext.getMerchantId();
        if (merchantId == null) {
            throw new BusinessException(ResultStatus.MERCHANT_ORDER_FORBIDDEN);
        }
        return merchantId;
    }
}
