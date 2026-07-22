package com.mall.module.user.service;

import com.mall.module.user.entity.LoginDTO;
import com.mall.module.user.entity.LoginVO;
import com.mall.module.user.entity.RegisterDTO;

public interface UserService {

    public abstract LoginVO register(RegisterDTO dto);

    public abstract LoginVO login(LoginDTO dto);

    public abstract LoginVO refresh();

}
