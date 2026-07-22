package com.mall.module.user.controller;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.Result;
import com.mall.module.user.entity.LoginDTO;
import com.mall.module.user.entity.LoginVO;
import com.mall.module.user.entity.RegisterDTO;
import com.mall.module.user.entity.User;
import com.mall.module.user.service.impl.UserServiceImpl;
import com.mall.security.utils.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/api")
public class AuthController {

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {

    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {

    }

    @GetMapping("/refresh")
    public Result<LoginVO> refresh() {

    }


}