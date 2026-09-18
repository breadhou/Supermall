package com.mall.module.admin.service;

import com.mall.module.admin.entity.dto.AdminCouponDTO;
import com.mall.module.admin.entity.dto.AdminSeckillActivityDTO;
import com.mall.module.admin.entity.vo.CouponTemplateVO;
import com.mall.module.admin.entity.vo.SeckillActivityVO;

public interface AdminMarketingService {

    SeckillActivityVO createSeckillActivity(AdminSeckillActivityDTO dto);

    CouponTemplateVO createCoupon(AdminCouponDTO dto);

}
