package com.mall.module.merchant.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mall.module.merchant.entity.vo.MerchantOrderItemVO;
import com.mall.module.merchant.entity.vo.MerchantOrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 本店订单查询。
 *
 * <p>{@code order} / {@code order_item} 都没有 {@code merchant_id}，归属只能经
 * {@code order_item → product_sku → product.merchant_id} 关联推导。这些查询刻意
 * 放在商家模块自己的 Mapper 里，不改动 order 模块。</p>
 */
@Mapper
public interface MerchantOrderMapper {

    /** 分页查询含本店商品的订单。 */
    IPage<MerchantOrderVO> selectStoreOrders(IPage<MerchantOrderVO> page,
                                             @Param("merchantId") Long merchantId,
                                             @Param("status") String status);

    /** 取指定订单中属于本店的明细行。 */
    List<MerchantOrderItemVO> selectStoreOrderItems(@Param("orderIds") List<Long> orderIds,
                                                    @Param("merchantId") Long merchantId);

    /** 该订单是否含本店商品；发货前的归属校验。 */
    long countStoreOrderItems(@Param("orderId") Long orderId,
                              @Param("merchantId") Long merchantId);

}
