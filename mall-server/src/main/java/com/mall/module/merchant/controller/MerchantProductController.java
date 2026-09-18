package com.mall.module.merchant.controller;

import com.mall.common.result.Result;
import com.mall.module.merchant.entity.dto.MerchantProductDTO;
import com.mall.module.merchant.entity.vo.MerchantProductVO;
import com.mall.module.merchant.service.MerchantProductService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/products")
public class MerchantProductController {

    private final MerchantProductService merchantProductService;

    public MerchantProductController(MerchantProductService merchantProductService) {
        this.merchantProductService = merchantProductService;
    }

    @PostMapping
    public Result<MerchantProductVO> create(@Valid @RequestBody MerchantProductDTO dto) {
        Result<MerchantProductVO> result = Result.build();
        result.success(merchantProductService.create(dto));
        return result;
    }

    @PutMapping("/{id}")
    public Result<MerchantProductVO> update(@PathVariable Long id,
                                            @Valid @RequestBody MerchantProductDTO dto) {
        Result<MerchantProductVO> result = Result.build();
        result.success(merchantProductService.update(id, dto));
        return result;
    }

    @PutMapping("/{id}/off-shelf")
    public Result<Void> offShelf(@PathVariable Long id) {
        merchantProductService.offShelf(id);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }
}
