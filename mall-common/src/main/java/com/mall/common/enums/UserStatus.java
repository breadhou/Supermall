package com.mall.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum UserStatus {

    NORMAL(1),
    DISABLED(0);

    @EnumValue
    private final int code;

    UserStatus(int code) {
        this.code = code;
    }
}
