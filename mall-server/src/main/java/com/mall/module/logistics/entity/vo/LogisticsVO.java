package com.mall.module.logistics.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LogisticsVO {

    private Long id;
    private Long orderId;
    private String company;
    private String trackingNo;
    private String status;
    private LocalDateTime createdAt;

}
