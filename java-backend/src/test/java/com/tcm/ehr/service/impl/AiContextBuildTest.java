package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.common.utils.LlmClient;
import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.AiReplyVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.ILogService;
import com.tcm.ehr.service.IStatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 上下文的<b>重复计算与重复查库</b>守护（P2-13 / P2-14）。
 *
 * <p>这两项都是纯省法，改错了不会报错、只会让某个块读到「另一份解析结果」：
 * 一次问答里 {@code load(recordId)} 被调两次、{@code structured} 被解析两次，
 * 或者核心缺项被算两遍。所以这里不去断言「少调用」这种实现细节本身，
 * 而是断言<b>块内注入的内容仍然完整</b>，同时钉住调用次数不回升。</p>
 */
class AiContextBuildTest {

    private RecordMapper baseMapper;
    private LlmClient llmClient;
    private QcRuleStore qcRuleStore;
    private AiServiceImpl svc;

    @BeforeEach
    void setUp() {
        baseMapper = mock(RecordMapper.class);
        IStatsService statsService = mock(IStatsService.class);
        llmClient = mock(LlmClient.class);
        ILogService logService = mock(ILogService.class);
        qcRuleStore = mock(QcRuleStore.class);
        IDictionaryTermStore termStore = mock(IDictionaryTermStore.class);

        svc = new AiServiceImpl(baseMapper, statsService, llmClient, new ObjectMapper(),
                logService, qcRuleStore, termStore);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentUsername", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void givenRecord() {
        Record r = new Record();
        r.setId("r1");
        r.setOrgId("org-A");
        r.setScore(95);
        r.setGrade("合格");
        r.setChiefComplaint("失眠3月");
        r.setTcmDiagnosis("不寐");
        r.setPattern("心脾两虚");
        r.setStructuredData("{\"symptoms\":[]}");
        when(baseMapper.selectById(any())).thenReturn(r);
    }

    private String buildContext(String question, String recordId) {
        return ReflectionTestUtils.invokeMethod(svc, "buildContext", question, recordId);
    }

    /**
     * P2-14：同一份病历在一次问答里只加载一次。
     *
     * <p>「归一」块与「当前病历」块都要这份病历；改造前两块各自 {@code load} 一次，
     * 一次提问就是两次 {@code selectById} + 两次 JSON 解析。</p>
     */
    @Test
    @DisplayName("问题同时命中归一块与当前病历块时，病历只查一次")
    void recordLoadedOnceForQuestionHittingTwoBlocks() {
        givenRecord();

        // 「归一」命中 standardBlock，「这份病历」命中 currentRecordBlock
        String ctx = buildContext("这份病历的归一情况怎么样", "r1");

        verify(baseMapper, times(1)).selectById("r1");
        assertTrue(ctx.contains("【归一/标准】"), "归一块应注入：" + ctx);
        assertTrue(ctx.contains("当前病历归一命中"), "归一块应带出命中分布：" + ctx);
        assertTrue(ctx.contains("【当前病历】"), "当前病历块应注入：" + ctx);
        assertTrue(ctx.contains("心脾两虚"), "当前病历块应带出辨证：" + ctx);
    }

    /** recordId 为空：不查库，且「当前病历」块仍给「未打开病历」的既有文案 */
    @Test
    @DisplayName("recordId 为空时不查库，当前病历块文案不变")
    void blankRecordIdSkipsQueryAndKeepsMessage() {
        String ctx = buildContext("这份病历怎么样", null);

        verify(baseMapper, times(0)).selectById(any());
        assertTrue(ctx.contains("未在详情中打开病历"), "空 id 仍应给出既有提示：" + ctx);
    }

    /**
     * P2-13：核心缺项在一次解读里只算一次。
     *
     * <p>{@code coreMissing} 内部要按当前组织取规则集（{@code qcRuleStore.getFor}），
     * 改造前 {@code keyHints} 会把它整条重算一遍 —— 规则集查一次、要素清单走两遍。</p>
     */
    @Test
    @DisplayName("解读时核心缺项只计算一次（规则集只取一次）")
    void coreMissingComputedOncePerInterpret() {
        givenRecord();

        AiQueryDTO dto = new AiQueryDTO();
        dto.setRecordId("r1");
        AiReplyVO vo = svc.interpret(dto);

        assertNotNull(vo);
        verify(qcRuleStore, times(1)).getFor(any());
        // 规则集被 mock 成 null → QcScorer 回落到 defaults()，四个核心要素都真缺失
        assertFalse(vo.getCoreMissing().isEmpty(), "核心缺项应被判出");
        // 提示里的项数必须与 vo.coreMissing 同源，否则就是两处口径各算各的
        String joined = String.join("\n", vo.getKeyHints());
        assertTrue(joined.contains("核心字段真缺失 " + vo.getCoreMissing().size() + " 项"),
                "keyHints 的项数应与 coreMissing 一致：" + joined);
        assertEquals(vo.getCoreMissing().size(),
                countNames(joined), "提示里列出的要素数应与 coreMissing 一致：" + joined);
    }

    /** 从「核心字段真缺失 N 项：a、b」里数出列了几项 */
    private static int countNames(String hints) {
        for (String line : hints.split("\n")) {
            int i = line.indexOf("核心字段真缺失 ");
            if (i < 0) continue;
            int colon = line.indexOf('：', i);
            if (colon < 0) return 0;
            String tail = line.substring(colon + 1).trim();
            return tail.isEmpty() ? 0 : tail.split("、").length;
        }
        return -1;
    }
}
