package com.mall.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.dto.CreateOrderDTO;
import com.mall.module.order.entity.dto.OrderPageDTO;
import com.mall.module.order.entity.dto.RefundDTO;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.OrderItem;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.OrderItemVO;
import com.mall.module.order.entity.vo.OrderListVO;
import com.mall.module.order.entity.vo.OrderVO;
import com.mall.module.order.mapper.OrderItemMapper;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.OrderService;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.mapper.AddressMapper;
import com.mall.security.utils.UserContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    OrderItemMapper orderItemMapper;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    RefundMapper refundMapper;

    @Autowired
    AddressMapper addressMapper;

    @Autowired
    ProductSkuMapper skuMapper;

    @Override
    @Transactional
    public OrderVO createOrder(CreateOrderDTO dto) {

        List<OrderItemVO> voList = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        Long userId = UserContext.getUserId();

        // 校验地址归属
        Address addr = addressMapper.selectById(dto.getAddressId());
        if (addr == null || !userId.equals(addr.getUserId())) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }

        Long orderId = SnowflakeIdUtil.nextId();

        for (CreateOrderDTO.OrderItemDTO itemDTO : dto.getItems()) {

            Long skuId = itemDTO.getSkuId();
            Integer quantity = itemDTO.getQuantity();

            ProductSku sku = skuMapper.selectById(skuId);
            if (sku == null) {
                throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
            }

            OrderItem item = new OrderItem()
                    .setId(SnowflakeIdUtil.nextId())
                    .setOrderId(orderId)
                    .setSkuId(skuId)
                    .setPrice(sku.getPrice())
                    .setQuantity(quantity);

            orderItemMapper.insert(item);

            OrderItemVO itemVO = new OrderItemVO();
            BeanUtils.copyProperties(item, itemVO);
            itemVO.setImage(sku.getImage());
            itemVO.setSpecs(sku.getSpecs());

            voList.add(itemVO);

            totalAmount = totalAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        Order order = new Order()
                .setId(orderId)
                .setOrderNo(String.valueOf(orderId))
                .setAddressId(dto.getAddressId())
                .setUserId(userId)
                .setTotalAmount(totalAmount)
                .setCouponId(dto.getCouponId())
                .setStatus("PENDING");

        orderMapper.insert(order);

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        orderVO.setItems(voList);

        return orderVO;

    }

    @Override
    public Page<OrderListVO> listOrders(OrderPageDTO dto) {

        Long userId = UserContext.getUserId();

        // 1. 构建分页 + 筛选条件
        Page<Order> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreatedAt);

        // 可选：按状态筛选
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            wrapper.eq(Order::getStatus, dto.getStatus());
        }

        // 2. 执行分页查询
        Page<Order> orderPage = orderMapper.selectPage(page, wrapper);

        // 3. 批量查询每个订单的 itemCount，避免 N+1
        List<Long> orderIds = orderPage.getRecords().stream()
                .map(Order::getId).toList();

        // group by order_id，统计每个订单的明细行数
        Map<Long, Long> itemCountMap = Map.of();
        if (!orderIds.isEmpty()) {
            LambdaQueryWrapper<OrderItem> countWrapper = new LambdaQueryWrapper<OrderItem>()
                    .in(OrderItem::getOrderId, orderIds);
            List<OrderItem> allItems = orderItemMapper.selectList(countWrapper);
            // 按 orderId 分组计数
            itemCountMap = allItems.stream()
                    .collect(Collectors.groupingBy(OrderItem::getOrderId, Collectors.counting()));
        }

        // 4. 转换为 OrderListVO 列表
        List<OrderListVO> voList = new ArrayList<>();
        for (Order order : orderPage.getRecords()) {
            OrderListVO vo = new OrderListVO();
            BeanUtils.copyProperties(order, vo);
            vo.setItemCount(itemCountMap.getOrDefault(order.getId(), 0L).intValue());
            voList.add(vo);
        }

        // 5. 构造返回的分页对象
        Page<OrderListVO> result = new Page<>(dto.getPageNum(), dto.getPageSize());
        result.setTotal(orderPage.getTotal());
        result.setRecords(voList);
        return result;

    }

    @Override
    public OrderVO getOrderDetail(Long orderId) {

        Long userId = UserContext.getUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        List<OrderItemVO> voList = new ArrayList<>();

        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId)
        );

        List<Long> skuIds = items.stream().map(OrderItem::getSkuId).toList();
        Map<Long, ProductSku> skuMap = skuMapper.selectByIds(skuIds)
                .stream().collect(Collectors.toMap(ProductSku::getId, s -> s));

        for (OrderItem item : items) {
            OrderItemVO itemVO = new OrderItemVO();
            BeanUtils.copyProperties(item, itemVO);  // 对象拷对象，同名字段：id, skuId, price, quantity
            ProductSku sku = skuMap.get(item.getSkuId());
            if (sku != null) {
                itemVO.setImage(sku.getImage());
                itemVO.setSpecs(sku.getSpecs());
            }
            voList.add(itemVO);
        }

        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        orderVO.setItems(voList);

        return orderVO;

    }

    @Override
    public void cancelOrder(Long orderId) {

        Long userId = UserContext.getUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        if (!order.getStatus().equals("PENDING")) {
            throw new BusinessException(ResultStatus.ORDER_STATUS_ERROR);
        }

        order.setStatus("CANCELLED");
        orderMapper.updateById(order);

    }

    @Override
    public void confirmReceipt(Long orderId) {

        Long userId = UserContext.getUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        if (!order.getStatus().equals("SHIPPED")) {
            throw new BusinessException(ResultStatus.ORDER_STATUS_ERROR);
        }

        order.setStatus("RECEIVED");
        orderMapper.updateById(order);

    }

    @Override
    public void requestRefund(Long orderId, RefundDTO dto) {

        Long userId = UserContext.getUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        if (!order.getStatus().equals("PAID") && !order.getStatus().equals("RECEIVED")) {
            throw new BusinessException(ResultStatus.ORDER_STATUS_ERROR);
        }

        Refund refund = new Refund()
                .setId(SnowflakeIdUtil.nextId())
                .setOrderId(order.getId())
                .setUserId(userId)
                .setReason(dto.getReason())
                .setAmount(order.getTotalAmount())
                .setStatus("PENDING");

        refundMapper.insert(refund);

    }


}
