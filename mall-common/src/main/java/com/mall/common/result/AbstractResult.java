package com.mall.common.result;

import com.mall.common.enums.ResultStatus;
import lombok.Getter;
import lombok.Setter;

public class AbstractResult {

    @Getter
    private ResultStatus status;
    @Setter
    @Getter
    private int code;
    private String message;

    AbstractResult() {
    }

    protected AbstractResult(ResultStatus status, String message) {
        this.code = status.getCode();
        this.status = status;
        this.message = message;
    }

    protected AbstractResult(ResultStatus status) {
        this.code = status.getCode();
        this.message = status.getMessage();
        this.status = status;
    }

    public static boolean isSuccess(AbstractResult result) {
        return result != null && result.status == ResultStatus.SUCCESS && result.getCode() == ResultStatus.SUCCESS.getCode();
    }

    public void withError(ResultStatus status) {
        this.status = status;
    }

    public void withError(String message) {
        this.status = ResultStatus.SYSTEM_ERROR;
        this.message = message;
    }

    public void withError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public void success() {
        this.status = ResultStatus.SUCCESS;
    }

    public String getMessage() {
        return this.message == null ? this.status.getMessage() : this.message;
    }

}
