package com.mall.module.seckill.mq;

import lombok.Data;

@Data
public class SeckillMessage {
    private Long userId;
    private Long seckillItemId;
    private String messageId;
    private Integer quantity;
}
