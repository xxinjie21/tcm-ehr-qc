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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 全局异常出口：把各类异常统一转成 {@link Result} 错误体。
 *
 * <p>状态码与业务错误码的分工：HTTP 状态表达"哪一类失败"，{@code code} 表达"具体原因"，
 * 前端据此提示用户；缺省都回 500 会把客户端错误说成系统故障，所以参数类异常一律落 400。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 用户名或密码错误。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 401 + code=401
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<Void>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(401, e.getMessage()));
    }

    /**
     * 业务参数非法。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /**
     * 数据域越权。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 403 + code=403
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result<Void>> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(403, e.getMessage()));
    }

    /**
     * LLM 连通性探测失败。
     *
     * <p>上游模型服务不可达或鉴权失败属网关侧故障，与参数错误区分；消息在抛出前已脱敏，可直接展示。</p>
     *
     * @param e 已脱敏的失败原因
     * @return HTTP 502 + code=1009
     */
    @ExceptionHandler(LlmProbeException.class)
    public ResponseEntity<Result<Void>> handleLlmProbe(LlmProbeException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(1009, e.getMessage()));
    }

    /**
     * 术语索引不可用。
     *
     * <p>归一只认 ES 索引、没有内存兜底，这条路径必须显式报错：按"未命中"处理会把服务故障
     * 显示成"词典里没这个词"。</p>
     *
     * @param e 索引不可用的原因
     * @return HTTP 503 + code=1010
     */
    @ExceptionHandler(TermIndexUnavailableException.class)
    public ResponseEntity<Result<Void>> handleTermIndexUnavailable(TermIndexUnavailableException e) {
        log.error("[全局异常] 术语索引不可用: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error(1010, e.getMessage()));
    }

    /**
     * 资源不存在（病历 ID 无效等）。
     *
     * @param e 携带资源类型与业务错误码
     * @return HTTP 404 + 异常自带错误码（默认 1006）
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 请求体字段校验失败（@Valid）。
     *
     * <p>单条错误直接用校验消息原文（已含中文字段说明）；多条才补字段名并全部返回，
     * 否则用户无法判断是哪一项出错。</p>
     *
     * @param e 校验失败异常
     * @return HTTP 400 + code=400，消息为全部校验问题
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        if (errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error(400, "请求参数非法"));
        }
        if (errors.size() == 1) {
            return ResponseEntity.badRequest().body(Result.error(400, messageOf(errors.get(0))));
        }
        String msg = errors.stream()
                .map(fe -> fe.getField() + "：" + messageOf(fe))
                .distinct()
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(Result.error(400, msg));
    }

    private static String messageOf(FieldError fe) {
        return fe.getDefaultMessage() == null ? "不合法" : fe.getDefaultMessage();
    }

    /**
     * 请求体缺失或 JSON 格式错误。
     *
     * <p>空 body / 坏 JSON 属客户端错误，落到兜底分支会被说成系统异常。</p>
     *
     * @param e 解析失败异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[全局异常] 请求体不可读: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.error(400, "请求体缺失或格式错误"));
    }

    /**
     * 缺少必填查询参数。
     *
     * @param e 缺失参数名
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, "缺少必填参数：" + e.getParameterName()));
    }

    /**
     * 参数类型不匹配（如路径参数非数字）。
     *
     * @param e 参数名
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, "参数类型不正确：" + e.getName()));
    }

    /**
     * 上传文件超过大小上限。
     *
     * @param e 超出上限的异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(Result.error(400, "文件超过大小上限（单文件/请求 50MB）"));
    }

    /**
     * 非 multipart 请求。
     *
     * @param e multipart 解析异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(MultipartException e) {
        return ResponseEntity.badRequest().body(Result.error(400, "请以 multipart/form-data 上传文件"));
    }

    /**
     * multipart 请求缺少文件部件。
     *
     * <p>该异常不是 {@link MultipartException} 子类，必须单独捕获。</p>
     *
     * @param e 缺失的部件名
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(400, "缺少文件参数：" + e.getRequestPartName()));
    }

    /**
     * 路由未匹配。
     *
     * <p>落兜底分支会被说成"系统异常"，掩盖真实的 404。</p>
     *
     * @param e 未命中路由的异常
     * @return HTTP 404 + code=404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "接口不存在"));
    }

    /**
     * 兜底异常。
     *
     * <p>响应带一个追踪码，与日志中的 trace 一致，用户可复制给管理员定位；追踪码不含业务信息。</p>
     *
     * @param e 未归类的异常
     * @return HTTP 500 + code=500，消息含追踪码
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        log.error("[全局异常][trace={}] {}", traceId, e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统异常，请联系管理员（追踪码 " + traceId + "）"));
    }
}
