package com.mall.module.user.controller;

import com.mall.common.result.Result;
import com.mall.module.user.entity.dto.AddressDTO;
import com.mall.module.user.entity.vo.AddressVO;
import com.mall.module.user.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @GetMapping("/address")
    public Result<List<AddressVO>> listAddresses() {
        List<AddressVO> list = addressService.listAddresses();
        Result<List<AddressVO>> result = Result.build();
        result.success(list);
        return result;
    }

    @PostMapping("/address")
    public Result<AddressVO> addAddress(@Valid @RequestBody AddressDTO dto) {
        AddressVO vo = addressService.addAddress(dto);
        Result<AddressVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @PutMapping("/address/{id}")
    public Result<AddressVO> updateAddress(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {
        AddressVO vo = addressService.updateAddress(id, dto);
        Result<AddressVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @DeleteMapping("/address/{id}")
    public Result<Void> deleteAddress(@PathVariable Long id) {
        addressService.deleteAddress(id);
        Result<Void> result = Result.build();
        result.success(null);
        return result;
    }
}
