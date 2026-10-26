package com.stockcat.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 统一异常处理：按 docs/API.md §1 的错误结构返回，不做 {code,data} 包装。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest req) {
        int status = ex.getErrorCode().httpStatus();
        return ResponseEntity.status(status)
                .body(ApiError.of(status, ex.getErrorCode().code(), ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, ErrorCode.VALIDATION_ERROR.code(), "请求参数校验失败", details, req.getRequestURI()));
    }

    /**
     * 方法参数校验失败（{@code @Min}/{@code @Max} 写在 {@code @RequestParam} 上）。
     *
     * <p>这类异常不是 MethodArgumentNotValidException，不单独接就会落到兜底的 500 ——
     * 用户传 `limit=9999` 得到一个"服务器内部错误"，看起来像后端崩了，实际是请求不合法。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        var details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, ErrorCode.VALIDATION_ERROR.code(), "请求参数校验失败", details, req.getRequestURI()));
    }

    /** Spring 6.1 起，未经 {@code @Validated} 的方法参数校验走这个异常。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleHandlerMethodValidation(HandlerMethodValidationException ex,
                                                                  HttpServletRequest req) {
        var details = ex.getAllErrors().stream()
                .map(e -> e.toString())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, ErrorCode.VALIDATION_ERROR.code(), "请求参数校验失败", details, req.getRequestURI()));
    }

    /** 参数类型不对（如把 abc 传给 limit）也应是 400，不是 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                      HttpServletRequest req) {
        String detail = ex.getName() + ": 期望 " + (ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName())
                + "，实际为 " + ex.getValue();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, ErrorCode.VALIDATION_ERROR.code(), "请求参数校验失败", List.of(detail), req.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(404)
                .body(ApiError.of(404, ErrorCode.NOT_FOUND.code(), "资源不存在", req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("未处理异常: {}", req.getRequestURI(), ex);
        return ResponseEntity.status(500)
                .body(ApiError.of(500, ErrorCode.INTERNAL_ERROR.code(), "服务器内部错误", req.getRequestURI()));
    }
}
