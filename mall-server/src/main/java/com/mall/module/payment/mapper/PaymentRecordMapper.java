package com.mall.module.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.payment.entity.po.PaymentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {

    @Select("SELECT * FROM payment_record WHERE order_id = #{orderId} LIMIT 1")
    PaymentRecord selectByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM payment_record WHERE order_id = #{orderId} LIMIT 1 FOR UPDATE")
    PaymentRecord selectByOrderIdForUpdate(@Param("orderId") Long orderId);

}
