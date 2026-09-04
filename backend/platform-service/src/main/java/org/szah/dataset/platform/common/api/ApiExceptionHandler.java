package org.szah.dataset.platform.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String, Object>> handleBusiness(BusinessException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "code", exception.code(),
                "message", exception.getMessage(),
                "details", List.of(),
                "request_id", requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_FAILED",
                "message", "请求参数校验失败",
                "details", details,
                "request_id", requestId(request)));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException exception,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(409).body(Map.of(
                "code", "BUSINESS_KEY_CONFLICT",
                "message", "业务唯一标识已存在",
                "details", List.of(),
                "request_id", requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute("request_id");
        return requestId == null ? "unknown" : requestId.toString();
    }
}
