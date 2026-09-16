package com.mall.module.logistics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.logistics.entity.po.Logistics;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LogisticsMapper extends BaseMapper<Logistics> {

    @Select("SELECT * FROM logistics WHERE order_id = #{orderId} LIMIT 1")
    Logistics selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM logistics WHERE order_id = #{orderId} LIMIT 1 FOR UPDATE")
    Logistics selectByOrderIdForUpdate(@Param("orderId") Long orderId);

}
