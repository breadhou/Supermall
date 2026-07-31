package com.mall.module.cart.service;

import com.mall.module.cart.entity.dto.AddCartItemDTO;
import com.mall.module.cart.entity.dto.UpdateCartItemDTO;
import com.mall.module.cart.entity.vo.CartItemVO;

import java.util.List;

public interface CartService {

    /** 加入购物车，同 SKU 则累加数量 */
    CartItemVO addItem(AddCartItemDTO dto);

    /** 当前用户购物车列表（含 SKU 展示信息） */
    List<CartItemVO> listItems();

    /** 修改数量 */
    CartItemVO updateQuantity(Long id, UpdateCartItemDTO dto);

    /** 删除单项 */
    void deleteItem(Long id);

    /** 清空购物车 */
    void clearCart();
}
