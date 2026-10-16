package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.common.utils.NlpAsyncTasks;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.domain.dto.NlpExtractDTO;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import com.tcm.ehr.domain.vo.NlpHealthVO;
import com.tcm.ehr.service.INlpBatchService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 批G·8.1：NlpController 契约 —— 空文本 400；服务不可用降级为空 9 类（modelAvailable=false）；
 * UX-63：返回前对实体做术语归一（content=标准词、sourceText=原文、normLevel=命中层级）。
 */
class NlpControllerTest {

    private NlpExtractDTO dto(String text) {
        return new NlpExtractDTO(text);
    }

    /** 单条抽取的异步池（NlpAsyncTasks 现从 Spring 取池）：本测试不跑异步路径，够用即可 */
    private static ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.initialize();
        return e;
    }

    /** 构造 controller；termNormalizer 传 null 表示该用例不关心归一行为 */
    private NlpController controller(PythonNlpClient client, EsTermNormalizer termNormalizer) {
        EsTermNormalizer normalizer = termNormalizer == null ? mock(EsTermNormalizer.class) : termNormalizer;
        // 批量解析依赖（批K 起构造器新增）：本测试不碰批量接口，用替身占位
        return new NlpController(client, new EntityNormalizer(normalizer, new tools.jackson.databind.ObjectMapper()),
                mock(INlpBatchService.class), mock(OperationLogger.class), new NlpAsyncTasks(executor()));
    }

    @Test
    void blankTextReturns400() {
        NlpController controller = controller(mock(PythonNlpClient.class), null);
        assertEquals(400, controller.extract(dto("   ")).getStatusCode().value());
        assertEquals(400, controller.extract(new NlpExtractDTO(null)).getStatusCode().value());
    }

    /**
     * 超长文本必须在 Java 入口被拦下，不能转发到 Python 拿 422 再降级。
     *
     * <p>这里不能走 {@code controller.extract(...)}：本测试是纯单元测试，没有 Spring MVC 的
     * {@code @Valid} 流程，Bean Validation 不会触发（上面的 {@code blankTextReturns400}
     * 测的其实是 Controller 里那行手写判空）。所以直接对 DTO 跑 Validator。</p>
     */
    @Test
    void textLongerThanLimitFailsValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        // 边界：正好 20000 通过（与 python-nlp/schemas.py 的 MAX_TEXT_CHARS 同值）
        assertTrue(validator.validate(new NlpExtractDTO("热".repeat(20000))).isEmpty());

        // 20001 必须报错，且提示语直接说明是长度问题 —— 前端 apiErrorMessage 会把它显示给用户，
        // 这正是修这个契约的目的：别再让用户看到「服务不可用」而误以为后端挂了
        Set<ConstraintViolation<NlpExtractDTO>> violations =
                validator.validate(new NlpExtractDTO("热".repeat(20001)));
        assertFalse(violations.isEmpty());
        assertEquals("文本最长 20000 字符", violations.iterator().next().getMessage());
    }

    @Test
    void unavailableDegradesToEmptyVo() {
        PythonNlpClient client = mock(PythonNlpClient.class);
        when(client.extract(anyString())).thenReturn(null);
        ResponseEntity<Result<NlpExtractVO>> resp = controller(client, null).extract(dto("发热"));
        assertEquals(200, resp.getStatusCode().value());
        NlpExtractVO vo = resp.getBody().getData();
        assertFalse(vo.isModelAvailable());
        assertEquals(0, vo.getDiseases().size());
        assertEquals(0, vo.getHerbs().size());
    }

    @Test
    void normalizesEntitiesBeforeReturning() {
        NlpExtractVO vo = NlpExtractVO.empty();
        vo.setModelAvailable(true);
        vo.getSymptoms().add(entity("咽痛"));
        vo.getTongueList().add(entity("舌红")); // 批次 20 起舌象有词典，会走归一
        vo.getHerbs().add(herb("双花"));

        PythonNlpClient client = mock(PythonNlpClient.class);
        when(client.extract(anyString())).thenReturn(vo);

        EsTermNormalizer termNormalizer = mock(EsTermNormalizer.class);
        when(termNormalizer.normalize(eq("symptom"), anyString(), eq("咽痛")))
                .thenReturn(new EsTermNormalizer.NormalizeResult("咽喉痛", 3));
        when(termNormalizer.normalize(eq("herb"), anyString(), eq("双花")))
                .thenReturn(new EsTermNormalizer.NormalizeResult("金银花", 1));
        // 舌象自批次 20 起有词典（tongues.json），与症状/中药同款走归一；
        // 这里返回未命中，验证「查不到就保留原文、不标命中级别」而不是被丢掉
        when(termNormalizer.normalize(eq("tongue"), anyString(), eq("舌红")))
                .thenReturn(new EsTermNormalizer.NormalizeResult("舌红", 0));

        ResponseEntity<Result<NlpExtractVO>> resp =
                controller(client, termNormalizer).extract(dto("咽痛"));
        NlpExtractVO out = resp.getBody().getData();

        NlpExtractVO.Entity symptom = out.getSymptoms().get(0);
        assertEquals("咽喉痛", symptom.getContent());
        assertEquals("咽痛", symptom.getSourceText());
        assertEquals(Integer.valueOf(3), symptom.getNormLevel());

        NlpExtractVO.Herb herb = out.getHerbs().get(0);
        assertEquals("金银花", herb.getName());
        assertEquals("双花", herb.getSourceText());
        assertEquals(Integer.valueOf(1), herb.getNormLevel());

        // 舌象查词未命中：content 保留原文、不标命中级别（批次 20 起舌象有词典，
        // 与从前「无词典 → 完全不查」的差别就在这里）
        assertEquals("舌红", out.getTongueList().get(0).getContent());
        assertEquals("舌红", out.getTongueList().get(0).getSourceText());
        assertNull(out.getTongueList().get(0).getNormLevel());
    }

    @Test
    void healthPassesProbeResultThrough() {
        // 探测结论完全由 PythonNlpClient.probe() 判定，Controller 只做转发：
        // 这里只验证「探测到什么就回什么」，判定口径不在这一层重复实现
        NlpHealthVO probe = new NlpHealthVO();
        probe.setEnabled(true);
        probe.setReachable(true);
        probe.setModelAvailable(false);
        probe.setUnavailableReason(NlpExtractVO.REASON_MODEL_MISSING);

        PythonNlpClient client = mock(PythonNlpClient.class);
        when(client.probe()).thenReturn(probe);

        Result<NlpHealthVO> result = controller(client, null).health();
        assertEquals(200, result.getCode());
        assertSame(probe, result.getData());
    }

    private NlpExtractVO.Entity entity(String text) {
        NlpExtractVO.Entity e = new NlpExtractVO.Entity();
        e.setContent(text);
        e.setSourceText(text);
        e.setSource("model");
        return e;
    }

    private NlpExtractVO.Herb herb(String name) {
        NlpExtractVO.Herb h = new NlpExtractVO.Herb();
        h.setName(name);
        h.setSourceText(name);
        h.setSource("model");
        return h;
    }
}
