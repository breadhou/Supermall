package com.mall.module.user.service;

import com.mall.module.user.entity.dto.LoginDTO;
import com.mall.module.user.entity.vo.LoginVO;
import com.mall.module.user.entity.dto.RegisterDTO;

public interface UserService {

    public abstract LoginVO register(RegisterDTO dto);

    public abstract LoginVO login(LoginDTO dto);

    public abstract LoginVO refresh();

}
