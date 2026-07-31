package com.mall.module.cart.controller;

import com.mall.common.result.Result;
import com.mall.module.cart.entity.dto.AddCartItemDTO;
import com.mall.module.cart.entity.dto.UpdateCartItemDTO;
import com.mall.module.cart.entity.vo.CartItemVO;
import com.mall.module.cart.service.CartService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    @Autowired
    private CartService cartService;

    @PostMapping
    public Result<CartItemVO> addItem(@Valid @RequestBody AddCartItemDTO dto) {
        CartItemVO vo = cartService.addItem(dto);
        Result<CartItemVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @GetMapping
    public Result<List<CartItemVO>> listItems() {
        List<CartItemVO> list = cartService.listItems();
        Result<List<CartItemVO>> result = Result.build();
        result.success(list);
        return result;
    }

    @PutMapping("/{id}")
    public Result<CartItemVO> updateQuantity(@PathVariable Long id, @Valid @RequestBody UpdateCartItemDTO dto) {
        CartItemVO vo = cartService.updateQuantity(id, dto);
        Result<CartItemVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteItem(@PathVariable Long id) {
        cartService.deleteItem(id);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }

    @DeleteMapping
    public Result<Void> clearCart() {
        cartService.clearCart();
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }
}
