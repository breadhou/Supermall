package com.mall.module.admin.mapper;

import com.mall.module.admin.entity.vo.AdminStatisticsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminStatisticsMapper {

    /**
     * 聚合 SQL 一次取回平台概览，避免多次往返。
     *
     * <p>GMV 只累加 PENDING 与 CANCELLED 之外的订单——前者尚未付款，
     * 后者已作废，计入会虚高。</p>
     */
    @Select("""
            SELECT (SELECT COUNT(*) FROM `user`)     AS userCount,
                   (SELECT COUNT(*) FROM merchant)   AS merchantCount,
                   (SELECT COUNT(*) FROM product)    AS productCount,
                   (SELECT COUNT(*) FROM `order`)    AS orderCount,
                   (SELECT COUNT(*) FROM `order`
                     WHERE status NOT IN ('PENDING', 'CANCELLED')) AS paidOrderCount,
                   (SELECT IFNULL(SUM(total_amount), 0) FROM `order`
                     WHERE status NOT IN ('PENDING', 'CANCELLED')) AS gmv
            """)
    AdminStatisticsVO selectStatistics();

}
