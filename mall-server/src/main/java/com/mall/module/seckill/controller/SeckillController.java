package com.mall.module.seckill.controller;

import com.mall.common.result.Result;
import com.mall.module.seckill.entity.vo.SeckillCountdownVO;
import com.mall.module.seckill.entity.vo.SeckillResultVO;
import com.mall.module.seckill.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀接口。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 库存预热已移至平台管理端 {@code POST /api/admin/seckill/items/{itemId}/preheat}。
     *
     * <p>原路径 {@code /api/seckill/{itemId}/preheat} 只要求已登录，任何注册用户
     * 都能把 Redis 库存重置为数据库快照——秒杀进行中调用会让可预占数量凭空增加。</p>
     */

    /**
     * 获取当前用户专属的秒杀路径。
     */
    @PostMapping("/{itemId}/path")
    public Result<String> getPath(@PathVariable Long itemId) {
        String path = seckillService.getPath(itemId);
        Result<String> result = Result.build();
        result.success(path);
        return result;
    }

    /**
     * 查询秒杀活动状态、价格和 Redis 剩余库存。
     */
    @GetMapping("/{itemId}/countdown")
    public Result<SeckillCountdownVO> getCountdown(@PathVariable Long itemId) {
        SeckillCountdownVO vo = seckillService.getCountdown(itemId);
        Result<SeckillCountdownVO> result = Result.build();
        result.success(vo);
        return result;
    }

    /**
     * 执行秒杀。请求体为空，path 由前置接口返回。
     */
    @PostMapping("/{itemId}/{path}")
    public Result<SeckillResultVO> executeSeckill(
            @PathVariable Long itemId,
            @PathVariable String path
    ) {
        SeckillResultVO vo = seckillService.executeSeckill(itemId, path);
        Result<SeckillResultVO> result = Result.build();
        result.success(vo);
        return result;
    }

    /**
     * 轮询异步秒杀结果。
     */
    @GetMapping("/result/{itemId}")
    public Result<SeckillResultVO> pollResult(@PathVariable Long itemId) {
        SeckillResultVO vo = seckillService.pollResult(itemId);
        Result<SeckillResultVO> result = Result.build();
        result.success(vo);
        return result;
    }
}
