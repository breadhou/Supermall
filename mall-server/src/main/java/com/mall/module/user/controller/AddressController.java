package com.mall.module.user.controller;

import com.mall.common.result.Result;
import com.mall.module.user.entity.dto.AddressDTO;
import com.mall.module.user.entity.vo.AddressVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
public class AddressController {

    @GetMapping("/address")
    public Result<List<AddressVO>> listAddresses() {
        // TODO 阶段二：实现地址列表查询
        throw new UnsupportedOperationException("listAddresses not implemented yet");
    }

    @PostMapping("/address")
    public Result<AddressVO> addAddress(@Valid @RequestBody AddressDTO dto) {
        // TODO 阶段二：实现新增地址
        throw new UnsupportedOperationException("addAddress not implemented yet");
    }

    @PutMapping("/address/{id}")
    public Result<AddressVO> updateAddress(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {
        // TODO 阶段二：实现修改地址
        throw new UnsupportedOperationException("updateAddress not implemented yet");
    }

    @DeleteMapping("/address/{id}")
    public Result<Void> deleteAddress(@PathVariable Long id) {
        // TODO 阶段二：实现删除地址
        throw new UnsupportedOperationException("deleteAddress not implemented yet");
    }
}
