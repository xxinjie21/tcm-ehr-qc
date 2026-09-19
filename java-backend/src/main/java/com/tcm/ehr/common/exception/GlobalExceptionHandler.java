package com.tcm.ehr.common.exception;

import com.tcm.ehr.common.domain.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：统一异常出口，全部返回 Result 错误体
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 认证失败（用户名或密码错误） -> HTTP 401 + code=401 */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<Void>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(401, e.getMessage()));
    }

    /** 业务参数非法 -> HTTP 400 + code=400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /** 请求体校验失败（@Valid） -> HTTP 400 + code=400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse("请求参数非法");
        return ResponseEntity.badRequest().body(Result.error(400, msg));
    }

    /**
     * 请求体缺失或 JSON 格式错误 -> HTTP 400 + code=400
     * （空 body / 坏 JSON 原会落到兜底分支返回 500，属客户端错误）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[全局异常] 请求体不可读: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.error(400, "请求体缺失或格式错误"));
    }

    /** 必填请求参数缺失 -> HTTP 400 + code=400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, "缺少必填参数：" + e.getParameterName()));
    }

    /** 参数类型不匹配（如路径/查询参数非数字） -> HTTP 400 + code=400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, "参数类型不正确：" + e.getName()));
    }

    /** 兜底异常 -> HTTP 500 + code=500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("[全局异常] {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统异常"));
    }
}
