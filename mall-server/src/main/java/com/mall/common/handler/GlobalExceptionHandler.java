package com.mall.common.handler;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice // 1. 声明这是一个全局异常处理器，其返回的所有对象都会自动转为 JSON
@Slf4j
public class GlobalExceptionHandler {

    // 2. 专门拦截并处理 Java 最臭名昭著的“空指针异常”
    @ExceptionHandler(NullPointerException.class)
    public Result<Void> handleNullPointerException(NullPointerException e) {
        log.error("发生空指针异常", e);
        return Result.fail(ResultStatus.EXCEPTION);
    }

    // 3. 专门拦截我们在业务中手动抛出的异常（如密码错误、余额不足）
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.error(e.getStatus().getMessage());
        return Result.fail(e.getStatus());
    }
    // 4. 请求方法不支持：属协议层错误，返回语义化的 405，不当作系统异常
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("request_method_not_allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ResultStatus.METHOD_NOT_ALLOWED));
    }

    // 5. 兜底拦截：拦截所有其他未预料到的系统异常（防止系统直接崩溃）
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("unknown_failure", e);
        return Result.fail(ResultStatus.EXCEPTION);
    }
}
