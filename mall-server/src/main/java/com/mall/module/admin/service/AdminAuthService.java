package com.mall.module.admin.service;

import com.mall.module.admin.entity.dto.AdminLoginDTO;
import com.mall.module.admin.entity.vo.AdminLoginVO;

public interface AdminAuthService {

    AdminLoginVO login(AdminLoginDTO dto);

}
