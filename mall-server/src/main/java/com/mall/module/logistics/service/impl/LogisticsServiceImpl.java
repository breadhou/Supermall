package com.mall.module.logistics.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.logistics.entity.dto.ShipOrderDTO;
import com.mall.module.logistics.entity.po.Logistics;
import com.mall.module.logistics.entity.vo.LogisticsVO;
import com.mall.module.logistics.mapper.LogisticsMapper;
import com.mall.module.logistics.service.LogisticsService;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.security.utils.UserContext;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class LogisticsServiceImpl implements LogisticsService {

    private static final String PAID = "PAID";
    private static final String SHIPPED = "SHIPPED";
    private static final String DELIVERED = "DELIVERED";

    private final LogisticsMapper logisticsMapper;
    private final OrderMapper orderMapper;

    public LogisticsServiceImpl(LogisticsMapper logisticsMapper, OrderMapper orderMapper) {
        this.logisticsMapper = logisticsMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    public LogisticsVO getLogistics(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        verifyOwnership(order);

        Logistics logistics = logisticsMapper.selectByOrderId(orderId);
        if (logistics == null) {
            throw new BusinessException(ResultStatus.LOGISTICS_NOT_EXIST);
        }
        return toVO(logistics);
    }

    @Override
    @Transactional
    public LogisticsVO shipOrder(Long orderId, ShipOrderDTO dto) {
        Order order = orderMapper.selectByIdForUpdate(orderId);
        verifyOwnership(order);
        return ship(order, dto);
    }

    @Override
    @Transactional
    public LogisticsVO shipOrderForMerchant(Long orderId, ShipOrderDTO dto) {
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }
        return ship(order, dto);
    }

    /** 状态流转本体，归属校验由各入口自行负责。 */
    private LogisticsVO ship(Order order, ShipOrderDTO dto) {
        Long orderId = order.getId();
        if (!PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultStatus.LOGISTICS_STATUS_ERROR);
        }
        if (dto == null || isBlank(dto.getCompany()) || isBlank(dto.getTrackingNo())) {
            throw new BusinessException(ResultStatus.PARAM_ERROR);
        }

        Logistics existing = logisticsMapper.selectByOrderIdForUpdate(orderId);
        if (existing != null) {
            throw new BusinessException(ResultStatus.LOGISTICS_STATUS_ERROR);
        }

        LocalDateTime createdAt = LocalDateTime.now();
        Logistics logistics = new Logistics()
                .setId(SnowflakeIdUtil.nextId())
                .setOrderId(orderId)
                .setCompany(dto.getCompany().trim())
                .setTrackingNo(dto.getTrackingNo().trim())
                .setStatus(SHIPPED)
                .setCreatedAt(createdAt);
        logisticsMapper.insert(logistics);

        order.setStatus(SHIPPED);
        orderMapper.updateById(order);
        return toVO(logistics);
    }

    @Override
    @Transactional
    public LogisticsVO markDelivered(Long orderId) {
        Order order = orderMapper.selectByIdForUpdate(orderId);
        verifyOwnership(order);
        return deliver(order);
    }

    @Override
    @Transactional
    public LogisticsVO markDeliveredForMerchant(Long orderId) {
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }
        return deliver(order);
    }

    /** 状态流转本体，归属校验由各入口自行负责。 */
    private LogisticsVO deliver(Order order) {
        Long orderId = order.getId();
        if (!SHIPPED.equals(order.getStatus())) {
            throw new BusinessException(ResultStatus.LOGISTICS_STATUS_ERROR);
        }

        Logistics logistics = logisticsMapper.selectByOrderIdForUpdate(orderId);
        if (logistics == null) {
            throw new BusinessException(ResultStatus.LOGISTICS_NOT_EXIST);
        }
        if (!SHIPPED.equals(logistics.getStatus())) {
            throw new BusinessException(ResultStatus.LOGISTICS_STATUS_ERROR);
        }

        logistics.setStatus(DELIVERED);
        logisticsMapper.updateById(logistics);
        // DELIVERED is additive to the existing order state machine.  The
        // user's existing /receive endpoint still moves DELIVERED to RECEIVED.
        order.setStatus(DELIVERED);
        orderMapper.updateById(order);
        return toVO(logistics);
    }

    private void verifyOwnership(Order order) {
        Long userId = UserContext.getUserId();
        if (order == null || userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LogisticsVO toVO(Logistics logistics) {
        LogisticsVO vo = new LogisticsVO();
        BeanUtils.copyProperties(logistics, vo);
        return vo;
    }

}
