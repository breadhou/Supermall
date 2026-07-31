package com.mall.module.order.entity.dto;

import lombok.Data;

@Data
public class OrderPageDTO {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    /** 可选筛选：PENDING/PAID/SHIPPED/RECEIVED/REFUNDED/CANCELLED */
    private String status;

}
