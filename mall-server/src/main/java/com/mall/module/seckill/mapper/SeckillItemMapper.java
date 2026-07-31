package com.mall.module.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.seckill.entity.po.SeckillItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillItemMapper extends BaseMapper<SeckillItem> {

    /**
     * 消费端的数据库库存兜底扣减，只有库存充足时才会更新。
     */
    @Update("""
            UPDATE seckill_item
            SET stock = stock - #{quantity}
            WHERE id = #{itemId}
              AND stock >= #{quantity}
            """)
    int decrementStock(@Param("itemId") Long itemId, @Param("quantity") Integer quantity);

}
