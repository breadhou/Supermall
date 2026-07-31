package com.mall.module.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.cart.entity.dto.AddCartItemDTO;
import com.mall.module.cart.entity.dto.UpdateCartItemDTO;
import com.mall.module.cart.entity.po.CartItem;
import com.mall.module.cart.entity.vo.CartItemVO;
import com.mall.module.cart.mapper.CartItemMapper;
import com.mall.module.cart.service.CartService;
import com.mall.module.product.entity.po.Product;
import com.mall.module.product.entity.po.ProductSku;
import com.mall.module.product.mapper.ProductMapper;
import com.mall.module.product.mapper.ProductSkuMapper;
import com.mall.security.utils.UserContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CartServiceImpl implements CartService {

    @Autowired
    private CartItemMapper cartItemMapper;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @Autowired
    private ProductMapper productMapper;

    @Override
    public CartItemVO addItem(AddCartItemDTO dto) {
        Long userId = UserContext.getUserId();

        // 校验 SKU 存在
        ProductSku sku = productSkuMapper.selectById(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }

        // 检查是否已在购物车中
        CartItem existing = cartItemMapper.selectOne(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, userId)
                        .eq(CartItem::getSkuId, dto.getSkuId())
        );

        CartItem item;
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + dto.getQuantity());
            cartItemMapper.updateById(existing);
            item = existing;
        } else {
            item = new CartItem()
                    .setId(SnowflakeIdUtil.nextId())
                    .setUserId(userId)
                    .setSkuId(dto.getSkuId())
                    .setQuantity(dto.getQuantity());
            cartItemMapper.insert(item);
        }

        return toVO(item, sku);
    }

    @Override
    public List<CartItemVO> listItems() {
        Long userId = UserContext.getUserId();

        List<CartItem> items = cartItemMapper.selectList(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, userId)
                        .orderByDesc(CartItem::getId)
        );

        if (items.isEmpty()) {
            return List.of();
        }

        // 批量查 SKU 和 Product 信息，避免 N+1
        List<Long> skuIds = items.stream().map(CartItem::getSkuId).toList();
        List<ProductSku> skus = productSkuMapper.selectByIds(skuIds);
        Map<Long, ProductSku> skuMap = skus.stream()
                .collect(Collectors.toMap(ProductSku::getId, s -> s));

        List<Long> productIds = skus.stream().map(ProductSku::getProductId).distinct().toList();
        List<Product> products = productMapper.selectByIds(productIds);
        Map<Long, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        List<CartItemVO> result = new ArrayList<>();
        for (CartItem item : items) {
            ProductSku sku = skuMap.get(item.getSkuId());
            if (sku == null) continue;
            Product product = productMap.get(sku.getProductId());
            result.add(toVO(item, sku, product));
        }
        return result;
    }

    @Override
    public CartItemVO updateQuantity(Long id, UpdateCartItemDTO dto) {
        Long userId = UserContext.getUserId();

        CartItem item = getOwnItem(id, userId);
        item.setQuantity(dto.getQuantity());
        cartItemMapper.updateById(item);

        ProductSku sku = productSkuMapper.selectById(item.getSkuId());
        return toVO(item, sku);
    }

    @Override
    public void deleteItem(Long id) {
        Long userId = UserContext.getUserId();

        getOwnItem(id, userId);
        cartItemMapper.deleteById(id);
    }

    @Override
    public void clearCart() {
        Long userId = UserContext.getUserId();

        cartItemMapper.delete(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getUserId, userId)
        );
    }

    /** 查询自己的购物车项，不存在则抛异常 */
    private CartItem getOwnItem(Long id, Long userId) {
        CartItem item = cartItemMapper.selectOne(
                new LambdaQueryWrapper<CartItem>()
                        .eq(CartItem::getId, id)
                        .eq(CartItem::getUserId, userId)
        );
        if (item == null) {
            throw new BusinessException(ResultStatus.DATA_NOT_FOUND);
        }
        return item;
    }

    private CartItemVO toVO(CartItem item, ProductSku sku) {
        CartItemVO vo = new CartItemVO();
        BeanUtils.copyProperties(item, vo);
        vo.setSpecs(sku.getSpecs());
        vo.setPrice(sku.getPrice());
        vo.setStock(sku.getStock());
        vo.setImage(sku.getImage());
        return vo;
    }

    private CartItemVO toVO(CartItem item, ProductSku sku, Product product) {
        CartItemVO vo = toVO(item, sku);
        if (product != null) {
            vo.setProductName(product.getName());
        }
        return vo;
    }
}
