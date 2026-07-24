package com.mall.module.user.service;

import com.mall.module.user.entity.dto.LoginDTO;
import com.mall.module.user.entity.vo.LoginVO;
import com.mall.module.user.entity.dto.RegisterDTO;

public interface UserService {

    LoginVO register(RegisterDTO dto);

    LoginVO login(LoginDTO dto);

    LoginVO refresh();

}
