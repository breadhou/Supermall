package com.mall.module.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.cart.entity.po.CartItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

}
