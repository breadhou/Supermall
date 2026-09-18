package com.mall.common.handler;

import com.mall.common.enums.ResultStatus;
import com.mall.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for the global exception handler.
 *
 * <p>A wrong HTTP method is a protocol error, not an unknown failure.  Letting
 * it fall through to the generic handler answered {@code -1 / 系统异常}, which
 * hid routing mistakes behind a status that looks like a server crash.</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleMethodNotSupported_shouldReportMethodNotAllowedInsteadOfUnknownFailure() {
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("GET");

        ResponseEntity<Result<Void>> response = handler.handleMethodNotSupported(exception);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(ResultStatus.METHOD_NOT_ALLOWED, body.getStatus());
        assertEquals(ResultStatus.METHOD_NOT_ALLOWED.getCode(), body.getCode());
    }
}
