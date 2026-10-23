package com.tcm.ehr.common.exception;

import com.tcm.ehr.common.domain.Result;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
 * 状态码与业务错误码的分工：HTTP 状态表达"哪一类失败"，code 表达"具体原因"，
 * 前端据此提示用户；缺省都回 500 会把客户端错误说成系统故障，所以参数类异常一律落 400。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 统一错误响应：HTTP 状态 + {@code Result{code,msg}}。
     *
     * 本类的 handler 几乎都是这个形状，原先各写一遍 {@code ResponseEntity.status(..).body(Result.error(..))}；
     * 收敛到此处一处，新增 handler 不会再漏掉统一形态（审查报告 §2.8）。
     */
    private static ResponseEntity<Result<Void>> error(HttpStatus status, int code, String msg) {
        return ResponseEntity.status(status).body(Result.error(code, msg));
    }

    /**
     * 用户名或密码错误。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 401 + code=401
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Result<Void>> handleBadCredentials(BadCredentialsException e) {
        return error(HttpStatus.UNAUTHORIZED, 401, e.getMessage());
    }

    /**
     * 凭证不可用（账号被停用等）。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 401 + code=401
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Result<Void>> handleUnauthorized(UnauthorizedException e) {
        return error(HttpStatus.UNAUTHORIZED, 401, e.getMessage());
    }

    /**
     * 业务参数非法。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, 400, e.getMessage());
    }

    /**
     * 通用业务异常（P3.6 下沉）：异常自带业务码与 HTTP 状态。
     *
     * 替代 Controller 里手拼 ResponseEntity.badRequest().body(Result.error(...))
     * 的错误体，让错误码/状态只在本处出口、外部可统一审计。
     *
     * @param e 携带 code / message / status
     * @return HTTP 状态取自异常 + 异常自带业务码
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return error(e.getStatus(), e.getCode(), e.getMessage());
    }

    /**
     * 数据域越权。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 403 + code=403
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result<Void>> handleForbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, 403, e.getMessage());
    }

    /**
     * LLM 连通性探测失败。
     *
     * 上游模型服务不可达或鉴权失败属网关侧故障，与参数错误区分；消息在抛出前已脱敏，可直接展示。
     *
     * @param e 已脱敏的失败原因
     * @return HTTP 502 + code=1009
     */
    @ExceptionHandler(LlmProbeException.class)
    public ResponseEntity<Result<Void>> handleLlmProbe(LlmProbeException e) {
        return error(HttpStatus.BAD_GATEWAY, 1009, e.getMessage());
    }

    /**
     * 术语索引不可用。
     *
     * 归一只认 ES 索引、没有内存兜底，这条路径必须显式报错：按"未命中"处理会把服务故障
     * 显示成"词典里没这个词"。
     *
     * @param e 索引不可用的原因
     * @return HTTP 503 + code=1010
     */
    @ExceptionHandler(TermIndexUnavailableException.class)
    public ResponseEntity<Result<Void>> handleTermIndexUnavailable(TermIndexUnavailableException e) {
        log.error("[全局异常] 术语索引不可用: {}", e.getMessage(), e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, 1010, e.getMessage());
    }

    /**
     * 服务未就绪（缺少必要配置，如未配 TCM_LLM_ENC_KEY）。
     *
     * 消息写明要配什么，是用户唯一能照着做的信息，故必须回显；落兜底只会给追踪码。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 503 + code=1011
     */
    @ExceptionHandler(ServiceNotReadyException.class)
    public ResponseEntity<Result<Void>> handleServiceNotReady(ServiceNotReadyException e) {
        log.error("[全局异常] 服务未就绪: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, 1011, e.getMessage());
    }

    /**
     * 触发接口限流（P2-31）。
     *
     * 消息已是面向用户的可展示文案（含「请稍后再试」），直接回显即可 ——
     * 落兜底只会给追踪码，用户不知道该怎么办。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 429 + code=429
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Result<Void>> handleRateLimited(RateLimitedException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, 429, e.getMessage());
    }

    /**
     * 资源不存在（病历 ID 无效等）。
     *
     * @param e 携带资源类型与业务错误码
     * @return HTTP 404 + 异常自带错误码（默认 1006）
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(ResourceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getCode(), e.getMessage());
    }

    /**
     * 请求体字段校验失败（@Valid）。
     *
     * 单条错误直接用校验消息原文（已含中文字段说明）；多条才补字段名并全部返回，
     * 否则用户无法判断是哪一项出错。
     *
     * @param e 校验失败异常
     * @return HTTP 400 + code=400，消息为全部校验问题
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        // 1. 拿不到字段错误就只给通用文案
        if (errors.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, 400, "请求参数非法");
        }
        // 2. 只有一个错误时直接说那一条，不要"字段：xxx"的前缀噪音
        if (errors.size() == 1) {
            return error(HttpStatus.BAD_REQUEST, 400, messageOf(errors.get(0)));
        }
        // 3. 多个错误逐条拼（去重：同一字段可能触发多条约束）
        String msg = errors.stream()
                .map(fe -> fe.getField() + "：" + messageOf(fe))
                .distinct()
                .collect(Collectors.joining("；"));
        return error(HttpStatus.BAD_REQUEST, 400, msg);
    }

    private static String messageOf(FieldError fe) {
        return fe.getDefaultMessage() == null ? "不合法" : fe.getDefaultMessage();
    }

    /**
     * 请求体缺失或 JSON 格式错误。
     *
     * 空 body / 坏 JSON 属客户端错误，落到兜底分支会被说成系统异常。
     *
     * @param e 解析失败异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[全局异常] 请求体不可读: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, 400, "请求体缺失或格式错误");
    }

    /**
     * 缺少必填查询参数。
     *
     * @param e 缺失参数名
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, 400, "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 参数类型不匹配（如路径参数非数字）。
     *
     * @param e 参数名
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, 400, "参数类型不正确：" + e.getName());
    }

    /**
     * 上传文件超过大小上限。
     *
     * @param e 超出上限的异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return error(HttpStatus.BAD_REQUEST, 400, "文件超过大小上限（单文件/请求 50MB）");
    }

    /**
     * 非 multipart 请求。
     *
     * @param e multipart 解析异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(MultipartException e) {
        return error(HttpStatus.BAD_REQUEST, 400, "请以 multipart/form-data 上传文件");
    }

    /**
     * multipart 请求缺少文件部件。
     *
     * 该异常不是 {@link MultipartException} 子类，必须单独捕获。
     *
     * @param e 缺失的部件名
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return error(HttpStatus.BAD_REQUEST, 400, "缺少文件参数：" + e.getRequestPartName());
    }

    /**
     * 路由未匹配。
     *
     * 落兜底分支会被说成"系统异常"，掩盖真实的 404。
     *
     * @param e 未命中路由的异常
     * @return HTTP 404 + code=404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, 404, "接口不存在");
    }

    /**
     * 请求方法不支持（如对只读端点发 POST）。
     *
     * 落兜底分支会被说成"系统异常"，前端也无法区分"接口不存在"与"方法用错"。
     *
     * @param e 方法不支持异常
     * @return HTTP 405 + code=405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, 405, "请求方法不支持：" + e.getMethod());
    }

    /**
     * 请求媒体类型不支持（如往只收 JSON 的端点发 form）。
     *
     * @param e 媒体类型异常
     * @return HTTP 415 + code=415
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 415, "请求内容类型不支持，请使用 application/json");
    }

    /**
     * 响应媒体类型不可接受（客户端 Accept 里没有服务端能产出的类型，
     * 例如带 {@code Accept: text/plain} 调一个 JSON 端点）。
     *
     * <b>为什么返回空 body（C1）</b>：走到这里说明内容协商已经失败过一次 ——
     * 再返回 Result 包装体，Spring 仍协商不出可写的媒体类型，会**再抛同一个异常**，
     * 最终落成 500。Spring 自带的 ResponseEntityExceptionHandler 对同一异常也是
     * 返回空 body 的 406，此处与之一致。修复前该异常没有处理器，直接落兜底的 500。
     *
     * @param e 媒体类型异常
     * @return HTTP 406（无 body）
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    /**
     * 方法级参数校验失败（@RequestParam 上的约束，如 @Min 分页下限）。
     *
     * 与 {@link MethodArgumentNotValidException}（请求体 Bean Validation）分开处理：
     * 后者取 BindingResult 的字段错误，本类只能从异常里取路径。
     *
     * @param e 约束校验异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        return error(HttpStatus.BAD_REQUEST, 400, e.getMessage());
    }

    /**
     * 表单/查询参数绑定失败（类型转换不合法等）。
     *
     * 与 {@link MethodArgumentNotValidException} 同源但不同类：前者带 BindingResult，
     * 本类只带一条消息。
     *
     * @param e 绑定异常
     * @return HTTP 400 + code=400
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String msg = e.getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return error(HttpStatus.BAD_REQUEST, 400, msg.isEmpty() ? "参数格式错误" : msg);
    }

    /**
     * 数据完整性约束冲突（唯一键 / 外键 / 非空）。
     *
     * 典型触发：并发撞唯一索引。直接落 500 会把"数据重复"说成"系统故障"，
     * 故落 409 并在日志里保留根因。
     *
     * @param e 完整性异常
     * @return HTTP 409 + code=409
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("[全局异常] 数据完整性约束冲突: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, 409, "数据冲突：该记录已存在或仍被其他数据引用");
    }

    /**
     * 并发互斥未获锁（同一操作正在进行）。
     *
     * 不是系统故障：用户只需稍后重试；落兜底 500 会让前端提示「系统异常（追踪码 …）」，
     * 把一句可操作的话说成故障，用户也不会知道「稍后重试」就是正解。
     *
     * @param e 携带可展示的提示文案
     * @return HTTP 409 + code=409
     */
    @ExceptionHandler(ConcurrentOperationException.class)
    public ResponseEntity<Result<Void>> handleConcurrentOperation(ConcurrentOperationException e) {
        log.warn("[全局异常] 并发互斥未获锁: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, 409, e.getMessage());
    }

    /**
     * 兜底异常。
     *
     * 响应带一个追踪码，与日志中的 trace 一致，用户可复制给管理员定位；追踪码不含业务信息。
     *
     * @param e 未归类的异常
     * @return HTTP 500 + code=500，消息含追踪码
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        // 1. 生成追踪码：用户报障时凭它定位日志，响应里不带堆栈
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        log.error("[全局异常][trace={}] {}", traceId, e.getMessage(), e);
        // 2. 异常原文只进日志，不回给前端
        return error(HttpStatus.INTERNAL_SERVER_ERROR, 500, "系统异常，请联系管理员（追踪码 " + traceId + "）");
    }
}
