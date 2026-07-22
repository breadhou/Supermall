package com.mall.common.utils;

import cn.hutool.core.util.IdUtil;

public class SnowflakeIdUtil {

    private SnowflakeIdUtil() {
    }

    public static long nextId() {
        return IdUtil.getSnowflake().nextId();
    }

    public static String nextIdStr() {
        return IdUtil.getSnowflake().nextIdStr();
    }
}