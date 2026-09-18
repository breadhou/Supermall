package com.mall.module.merchant.controller;

import com.mall.common.result.Result;
import com.mall.module.merchant.entity.dto.MerchantLoginDTO;
import com.mall.module.merchant.entity.vo.MerchantLoginVO;
import com.mall.module.merchant.service.MerchantAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant")
public class MerchantAuthController {

    private final MerchantAuthService merchantAuthService;

    public MerchantAuthController(MerchantAuthService merchantAuthService) {
        this.merchantAuthService = merchantAuthService;
    }

    @PostMapping("/login")
    public Result<MerchantLoginVO> login(@Valid @RequestBody MerchantLoginDTO dto) {
        Result<MerchantLoginVO> result = Result.build();
        result.success(merchantAuthService.login(dto));
        return result;
    }
}
