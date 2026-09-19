package com.mall.module.order.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 退款原因。刻意只有一个字段——金额不接受客户端传入。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundReasonDTO {

    @NotBlank
    @Size(max = 512)
    private String reason;
}
