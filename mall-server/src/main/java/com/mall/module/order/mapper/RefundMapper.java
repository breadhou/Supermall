package com.mall.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.order.entity.po.Refund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundMapper extends BaseMapper<Refund> {

    /**
     * 锁定读该订单的退款行，读最新已提交版本而非快照。
     * 用于并发重试的兜底：此时本事务的 read view 已过期，普通 SELECT 看不到赢家。
     */
    @Select("SELECT * FROM refund WHERE order_id = #{orderId} LIMIT 1 FOR UPDATE")
    Refund selectByOrderIdForUpdate(@Param("orderId") Long orderId);

}
