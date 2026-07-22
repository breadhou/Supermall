package com.mall.common.exception;

import com.mall.common.enums.ResultStatus;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class BusinessException extends RuntimeException {

    private ResultStatus status;

    public BusinessException(ResultStatus status) {
        super();
        this.status = status;
    }

}
