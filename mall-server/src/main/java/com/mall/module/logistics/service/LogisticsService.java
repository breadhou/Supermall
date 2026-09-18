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

    /**
     * Ship on behalf of a merchant.  The caller has already established that
     * the order contains items belonging to the current merchant, so this
     * entry point deliberately skips the C-end ownership check — that check
     * compares against {@code UserContext}, which is empty on a merchant
     * request.
     */
    LogisticsVO shipOrderForMerchant(Long orderId, ShipOrderDTO dto);

    /** Deliver on behalf of a merchant.  Same ownership reasoning as above. */
    LogisticsVO markDeliveredForMerchant(Long orderId);

}
