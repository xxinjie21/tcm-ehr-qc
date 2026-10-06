package com.tcm.ehr.common.exception;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.controller.DictionaryController;
import com.tcm.ehr.service.IDictionaryService;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常出口的「客户端错误不得回 500」契约测试。
 *
 * 这两类异常都继承 jakarta.servlet.ServletException，原先会落进
 * @ExceptionHandler(Exception.class) 兜底，客户端拿到的是 500「系统异常」——
 * 既掩盖真实原因，也让前端无法区分「接口不存在」与「服务出错」。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** multipart 请求缺 file 部件 -> 400（不是 500） */
    @Test
    void missingFilePart_shouldBe400() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new DictionaryController(Mockito.mock(IDictionaryService.class),
                                Mockito.mock(com.tcm.ehr.service.IDictProposalService.class),
                                Mockito.mock(com.tcm.ehr.service.IDictArchiveService.class),
                                // 批次 21 起 controller 多一个词表体检依赖
                                Mockito.mock(com.tcm.ehr.service.IDictionaryLintService.class),
                                Mockito.mock(OperationLogger.class),
                                // 批次 6 工作项 4 起 controller 多一个词典写权限依赖
                                Mockito.mock(com.tcm.ehr.service.IOrgPermissionService.class)))
                .setControllerAdvice(handler)
                .build();

        mockMvc.perform(multipart("/api/dictionary/import").param("type", "symptom"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("缺少文件参数：file"));
    }

    /**
     * 未匹配路由 -> 404（不是 500）。
     * standalone MockMvc 没有静态资源兜底，抛不出 NoResourceFoundException，故直接验证 handler 的映射。
     */
    @Test
    void unknownRoute_shouldBe404() {
        ResponseEntity<Result<Void>> resp = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/api/no-such-endpoint", "/api/no-such-endpoint"));

        assertEquals(404, resp.getStatusCode().value());
        assertEquals(404, resp.getBody().getCode());
    }

    /** 缺 file 部件的映射单测：直接喂异常，锁定状态码与提示文案 */
    @Test
    void missingFilePartMapping_shouldCarryPartName() {
        ResponseEntity<Result<Void>> resp = handler.handleMissingPart(
                new MissingServletRequestPartException("file"));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals(400, resp.getBody().getCode());
        assertEquals("缺少文件参数：file", resp.getBody().getMsg());
    }

    // ------------------------------------------------ 批次 2 新增：客户端错误语义

    /** 方法不支持 -> 405（原先落兜底 500） */
    @Test
    void methodNotSupported_shouldBe405() {
        ResponseEntity<Result<Void>> resp = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"));

        assertEquals(405, resp.getStatusCode().value());
        assertEquals(405, resp.getBody().getCode());
    }

    /** 媒体类型不支持 -> 415 */
    @Test
    void mediaTypeNotSupported_shouldBe415() {
        ResponseEntity<Result<Void>> resp = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                        List.of(MediaType.APPLICATION_JSON)));

        assertEquals(415, resp.getStatusCode().value());
        assertEquals(415, resp.getBody().getCode());
    }

    /** 方法级参数校验失败 -> 400 */
    @Test
    void constraintViolation_shouldBe400() {
        ResponseEntity<Result<Void>> resp = handler.handleConstraintViolation(
                new ConstraintViolationException("pageSize 必须大于 0", Set.of()));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("pageSize 必须大于 0", resp.getBody().getMsg());
    }

    /** 唯一键冲突 -> 409（不是 500）：并发撞索引最常见的出口 */
    @Test
    void dataIntegrityViolation_shouldBe409() {
        ResponseEntity<Result<Void>> resp = handler.handleDataIntegrity(
                new DataIntegrityViolationException("Duplicate entry 'admin' for key 'uk_username'"));

        assertEquals(409, resp.getStatusCode().value());
        assertEquals(409, resp.getBody().getCode());
    }

    /** 账号停用 -> 401（不是行级越权的 403） */
    @Test
    void unauthorized_shouldBe401() {
        ResponseEntity<Result<Void>> resp = handler.handleUnauthorized(
                new UnauthorizedException("账号已被停用，请联系管理员"));

        assertEquals(401, resp.getStatusCode().value());
        assertEquals(401, resp.getBody().getCode());
    }

    // ------------------------------------------------ 批次 25 工作项 1：状态类异常不再落兜底 500

    /** 并发互斥未获锁 -> 409 + 原文案（原先落兜底 500，用户只看到「系统异常（追踪码 …）」） */
    @Test
    void concurrentOperation_shouldBe409() {
        ResponseEntity<Result<Void>> resp = handler.handleConcurrentOperation(
                new ConcurrentOperationException("有另一个相同操作正在进行，请稍后重试"));

        assertEquals(409, resp.getStatusCode().value());
        assertEquals(409, resp.getBody().getCode());
        assertEquals("有另一个相同操作正在进行，请稍后重试", resp.getBody().getMsg());
    }

    /** 未配置加密密钥 -> 503 + code=1011，且回显「该配什么」（兜底分支只会给追踪码） */
    @Test
    void serviceNotReady_shouldBe503WithActionableMessage() {
        ResponseEntity<Result<Void>> resp = handler.handleServiceNotReady(
                new ServiceNotReadyException("未配置 LLM 加密密钥，无法保存密钥（请联系管理员配置 TCM_LLM_ENC_KEY）"));

        assertEquals(503, resp.getStatusCode().value());
        assertEquals(1011, resp.getBody().getCode());
        assertTrue(resp.getBody().getMsg().contains("TCM_LLM_ENC_KEY"));
    }
}
