package com.mall.module.logistics.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShipOrderDTO {

    @NotBlank
    @Size(max = 64)
    private String company;

    @NotBlank
    @Size(max = 64)
    private String trackingNo;

}
