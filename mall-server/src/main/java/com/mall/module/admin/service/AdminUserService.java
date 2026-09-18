package com.mall.module.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mall.module.admin.entity.dto.AdminUserPageDTO;
import com.mall.module.admin.entity.vo.AdminUserVO;

public interface AdminUserService {

    IPage<AdminUserVO> listUsers(AdminUserPageDTO dto);

    void updateStatus(Long userId, Integer status);

}
