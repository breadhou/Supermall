package com.mall.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.order.entity.po.Refund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundMapper extends BaseMapper<Refund> {

    /**
     * 锁定读该订单的退款行。
     *
     * <p>并发重试的兜底专用：进入 catch 时本事务的 read view **早于赢家提交**（见 Task 5 修订说明），
     * 普通 SELECT 复用旧 view 必然读不到赢家，只有锁定读才读最新已提交版本。</p>
     */
    @Select("SELECT * FROM refund WHERE order_id = #{orderId} LIMIT 1 FOR UPDATE")
    Refund selectByOrderIdForUpdate(@Param("orderId") Long orderId);

}
