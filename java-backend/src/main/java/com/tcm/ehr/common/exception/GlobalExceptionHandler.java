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

    /**
     * LLM 连通性探测失败 -> HTTP 502 + code=1009。
     *
     * <p>上游模型服务不可达 / 鉴权失败属「网关侧故障」，与客户端参数错误（400）区分开；
     * 消息在抛出前已脱敏（抹掉 api-key），可直接展示给用户。</p>
     */
    @ExceptionHandler(LlmProbeException.class)
    public ResponseEntity<Result<Void>> handleLlmProbe(LlmProbeException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(1009, e.getMessage()));
    }

    /**
     * 术语索引不可用 -> HTTP 503 + code=1010。
     *
     * <p>归一自 2026-09-23 起只认 ES 索引、没有内存兜底，所以这条路径必须显式报出来：
     * 若按「未命中」处理，页面上会显示成「词典里没收录这个词」，把服务故障说成词典缺词。</p>
     */
    @ExceptionHandler(TermIndexUnavailableException.class)
    public ResponseEntity<Result<Void>> handleTermIndexUnavailable(TermIndexUnavailableException e) {
        log.error("[全局异常] 术语索引不可用: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error(1010, e.getMessage()));
    }

    /** 资源不存在（病历 ID 无效等） -> HTTP 404 + 业务错误码（默认 1006） */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 请求体校验失败（@Valid） -> HTTP 400 + code=400。
     *
     * <p>单条错误直接用校验消息原文（消息本身已含中文字段说明，再拼英文字段名反而冗长）；
     * <b>多条错误才补字段名并一次全部返回</b>，否则用户无法判断是哪一项出错（UX-06）。</p>
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

    /**
     * 兜底异常 -> HTTP 500 + code=500。
     *
     * <p>响应里带一个**追踪码**，与日志中的 trace 一致，用户可直接复制给管理员定位（UX-06）。
     * 追踪码不含任何业务信息，仅用于关联日志。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        log.error("[全局异常][trace={}] {}", traceId, e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统异常，请联系管理员（追踪码 " + traceId + "）"));
    }
}
