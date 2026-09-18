package com.mall.module.merchant.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品的创建与编辑入参。
 *
 * <p>价格与库存都在 SKU 上，因此创建 SPU 时一并提交 SKU 列表，同事务写入。</p>
 *
 * <p><b>编辑语义为全量覆盖</b>：带 {@code id} 的 SKU 做更新，不带的做新增，
 * 库中存在但请求未提及的 SKU 会被删除。</p>
 */
@Data
public class MerchantProductDTO {

    @NotBlank
    @Size(max = 256)
    private String name;

    @Size(max = 2000)
    private String description;

    @NotNull
    private Long categoryId;

    /** 可空；创建时默认 ON_SHELF，允许 DRAFT。 */
    private String status;

    @NotEmpty
    @Valid
    private List<SkuDTO> skus;

    @Data
    public static class SkuDTO {

        /** 编辑时携带表示已有 SKU；为空表示新增。 */
        private Long id;

        @Size(max = 512)
        private String specs;

        @NotNull
        @DecimalMin("0.01")
        private BigDecimal price;

        @NotNull
        @Min(0)
        private Integer stock;

        @Size(max = 512)
        private String image;

    }
}
