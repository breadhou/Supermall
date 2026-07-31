package com.mall.module.seckill.entity.vo;

import lombok.Data;

@Data
public class SeckillResultVO {

    /** WAITING / SUCCESS / FAILED */
    private String status;
    /** 成功时返回订单 ID */
    private Long orderId;
    /** 失败时返回原因 */
    private String reason;

}
