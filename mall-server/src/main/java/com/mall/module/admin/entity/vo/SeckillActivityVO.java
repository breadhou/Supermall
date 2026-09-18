package com.mall.module.admin.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class SeckillActivityVO {

    private Long id;
    private String name;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private List<ItemVO> items;

    @Data
    @Accessors(chain = true)
    public static class ItemVO {

        private Long seckillItemId;
        private Long skuId;
        private BigDecimal seckillPrice;
        private Integer stock;
        private Integer limitPerUser;

    }
}
