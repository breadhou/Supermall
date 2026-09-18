package com.mall.module.admin.service.impl;

import com.mall.module.admin.entity.vo.AdminStatisticsVO;
import com.mall.module.admin.mapper.AdminStatisticsMapper;
import com.mall.module.admin.service.AdminStatisticsService;
import org.springframework.stereotype.Service;

@Service
public class AdminStatisticsServiceImpl implements AdminStatisticsService {

    private final AdminStatisticsMapper adminStatisticsMapper;

    public AdminStatisticsServiceImpl(AdminStatisticsMapper adminStatisticsMapper) {
        this.adminStatisticsMapper = adminStatisticsMapper;
    }

    @Override
    public AdminStatisticsVO overview() {
        return adminStatisticsMapper.selectStatistics();
    }
}
