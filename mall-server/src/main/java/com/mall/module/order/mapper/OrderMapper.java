package com.mall.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.order.entity.po.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * Lock the order row while payment or logistics changes its state.
     * The row lock makes the "one payment/one shipment per order" transition
     * safe when the same request is retried concurrently.
     */
    @Select("SELECT * FROM `order` WHERE id = #{orderId} LIMIT 1 FOR UPDATE")
    Order selectByIdForUpdate(@Param("orderId") Long orderId);

}
