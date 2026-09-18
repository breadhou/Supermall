package com.mall.module.admin.service;

import com.mall.module.admin.entity.dto.AdminCouponDTO;
import com.mall.module.admin.entity.dto.AdminSeckillActivityDTO;
import com.mall.module.admin.entity.vo.CouponTemplateVO;
import com.mall.module.admin.entity.vo.SeckillActivityVO;

public interface AdminMarketingService {

    SeckillActivityVO createSeckillActivity(AdminSeckillActivityDTO dto);

    CouponTemplateVO createCoupon(AdminCouponDTO dto);

    /**
     * 预热秒杀商品库存。
     *
     * <p>这是运营动作而非用户动作：它把 Redis 库存**重置**为数据库里的
     * {@code seckill_item.stock} 并重写快照。挂在 C 端路径上时任何注册用户
     * 都能调用，秒杀进行中调用会让可预占数量凭空增加——因此收归管理端。</p>
     */
    void preheatSeckillItem(Long seckillItemId);

}
