package com.mall.module.user.controller;

import com.mall.common.result.Result;
import com.mall.module.user.entity.dto.LoginDTO;
import com.mall.module.user.entity.vo.LoginVO;
import com.mall.module.user.entity.dto.RegisterDTO;
import com.mall.module.user.service.impl.UserServiceImpl;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    UserServiceImpl userService;

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        LoginVO vo = userService.register(dto);
        Result<LoginVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        LoginVO vo = userService.login(loginDTO);
        Result<LoginVO> result = Result.build();
        result.success(vo);
        return result;
    }

    @GetMapping("/refresh")
    public Result<LoginVO> refresh() {
        LoginVO vo = userService.refresh();
        Result<LoginVO> result = Result.build();
        result.success(vo);
        return result;
    }


}