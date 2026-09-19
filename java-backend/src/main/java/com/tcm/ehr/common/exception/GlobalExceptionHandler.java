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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** 数据域越权（行级权限） -> HTTP 403 + code=403 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result<Void>> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(403, e.getMessage()));
    }

    /** 资源不存在（病历 ID 无效等） -> HTTP 404 + 业务错误码（默认 1006） */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(e.getCode(), e.getMessage()));
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

    /** 上传文件超过大小上限（单文件/请求 50MB） -> HTTP 400 + code=400 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(Result.error(400, "文件超过大小上限（单文件/请求 50MB）"));
    }

    /** 非 multipart 请求或文件缺失 -> HTTP 400 + code=400（客户端错误，非 500） */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(MultipartException e) {
        return ResponseEntity.badRequest().body(Result.error(400, "请以 multipart/form-data 上传文件"));
    }

    /**
     * multipart 请求里缺少文件部件（未带 file 字段） -> HTTP 400 + code=400
     * （MissingServletRequestPartException 不是 MultipartException 的子类，抓不到上面那个分支）
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, "缺少文件参数：" + e.getRequestPartName()));
    }

    /** 未匹配到任何路由 -> HTTP 404（否则被兜底吞成 500「系统异常」，掩盖了真实原因） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "接口不存在"));
    }

    /** 兜底异常 -> HTTP 500 + code=500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("[全局异常] {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统异常"));
    }
}
