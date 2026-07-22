package com.mall.common.result;

import com.mall.common.enums.ResultStatus;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

@Setter
@Getter
public class Result<T> extends AbstractResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 867933019328199779L;
    private T data;
    private Integer count;

    protected Result() {
    }

    protected Result(ResultStatus status, String message) {
        super(status, message);
    }

    protected Result(ResultStatus status) {
        super(status);
    }

    public static <T> Result<T> build() {
        return new Result(ResultStatus.SUCCESS, (String) null);
    }

    public static <T> Result<T> build(String message) {
        return new Result(ResultStatus.SUCCESS, message);
    }

    public static <T> Result<T> fail(ResultStatus status) {
        return new Result<T>(status);
    }

    public void success(T value) {
        this.success();
        this.data = value;
        this.count = 0;
    }

}