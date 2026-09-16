package com.mall.module.logistics.service;

import com.mall.module.logistics.entity.dto.ShipOrderDTO;
import com.mall.module.logistics.entity.vo.LogisticsVO;

public interface LogisticsService {

    /** Query logistics for the current user's order. */
    LogisticsVO getLogistics(Long orderId);

    /**
     * Create a shipment for a paid order.
     * This service operation is intentionally kept for the future merchant
     * module; the public API currently exposes query only.
     */
    LogisticsVO shipOrder(Long orderId, ShipOrderDTO dto);

    /**
     * Move an existing shipment to DELIVERED.
     * The merchant integration in phase nine will call this operation.
     */
    LogisticsVO markDelivered(Long orderId);

}
