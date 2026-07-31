package com.mall.module.seckill.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SeckillCountdownVO {

    /** NOT_STARTED / IN_PROGRESS / ENDED */
    private String activityStatus;
    /** 活动开始时间 */
    private LocalDateTime startTime;
    /** 活动结束时间 */
    private LocalDateTime endTime;
    /** 秒杀价 */
    private BigDecimal seckillPrice;
    /** Redis 实时剩余库存 */
    private Integer remainingStock;
    /** 每人限购数 */
    private Integer limitPerUser;

}
