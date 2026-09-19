package com.tcm.ehr.common.exception;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.controller.DictionaryController;
import com.tcm.ehr.service.IDictionaryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常出口的「客户端错误不得回 500」契约测试。
 *
 * <p>这两类异常都继承 {@code jakarta.servlet.ServletException}，原先会落进
 * {@code @ExceptionHandler(Exception.class)} 兜底，客户端拿到的是 500「系统异常」——
 * 既掩盖真实原因，也让前端无法区分「接口不存在」与「服务出错」。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** multipart 请求缺 file 部件 -> 400（不是 500） */
    @Test
    void missingFilePart_shouldBe400() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new DictionaryController(Mockito.mock(IDictionaryService.class),
                                Mockito.mock(OperationLogger.class)))
                .setControllerAdvice(handler)
                .build();

        mockMvc.perform(multipart("/api/dictionary/convert").param("type", "symptom"))
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
}
