package com.mall.module.seckill.service;

import com.mall.module.seckill.entity.vo.SeckillCountdownVO;
import com.mall.module.seckill.entity.vo.SeckillResultVO;

public interface SeckillService {

    void preheatStock(Long itemId);
    String getPath(Long itemId);
    SeckillCountdownVO getCountdown(Long itemId);
    SeckillResultVO executeSeckill(Long itemId, String path);
    SeckillResultVO pollResult(Long itemId);

}
