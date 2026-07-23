package com.mall.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.module.user.entity.po.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {

}