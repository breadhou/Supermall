package com.mall.module.user.controller;

import com.mall.common.result.Result;
import com.mall.module.user.entity.AddressDTO;
import com.mall.module.user.entity.AddressVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/address")
public class AddressController {
    @GetMapping("/address")
    public Result<List<AddressVO>> listAddresses() {

    }

    @PostMapping("/address")
    public Result<AddressVO> addAddress(@Valid @RequestBody AddressDTO dto) {

    }

    @PutMapping("/address/{id}")
    public Result<AddressVO> updateAddress(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {

    }

    @DeleteMapping("/address/{id}")
    public Result<Void> deleteAddress(@PathVariable Long id) {

    }
}
