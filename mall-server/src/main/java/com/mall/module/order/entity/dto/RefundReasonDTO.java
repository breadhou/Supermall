package com.mall.module.order.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 退款原因与可选的成对复核前置条件；金额仍不接受客户端传入。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundReasonDTO {

    @NotBlank
    @Size(max = 512)
    private String reason;

    private String expectedCatalogFingerprint;
    private String expectedPolicyCode;

    /** 保留既有只传原因的直接 HTTP 调用。 */
    public RefundReasonDTO(String reason) {
        this.reason = reason;
    }
}
