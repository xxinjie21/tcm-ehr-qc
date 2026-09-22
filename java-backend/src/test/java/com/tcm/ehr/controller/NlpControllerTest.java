package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.domain.dto.NlpExtractDTO;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批G·8.1：NlpController 契约 —— 空文本 400；服务不可用降级为空 9 类（modelAvailable=false）；
 * UX-63：返回前对实体做术语归一（content=标准词、sourceText=原文、normLevel=命中层级）。
 */
class NlpControllerTest {

    private NlpExtractDTO dto(String text) {
        NlpExtractDTO d = new NlpExtractDTO();
        d.setText(text);
        return d;
    }

    /** 构造 controller；termNormalizer 传 null 表示该用例不关心归一行为 */
    private NlpController controller(PythonNlpClient client, EsTermNormalizer termNormalizer) {
        EsTermNormalizer normalizer = termNormalizer == null ? mock(EsTermNormalizer.class) : termNormalizer;
        return new NlpController(client, new EntityNormalizer(normalizer));
    }

    @Test
    void blankTextReturns400() {
        NlpController controller = controller(mock(PythonNlpClient.class), null);
        assertEquals(400, controller.extract(dto("   ")).getStatusCode().value());
        assertEquals(400, controller.extract(new NlpExtractDTO()).getStatusCode().value());
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
        vo.getTongueList().add(entity("舌红")); // 无独立词典 → 只保留原文
        vo.getHerbs().add(herb("双花"));

        PythonNlpClient client = mock(PythonNlpClient.class);
        when(client.extract(anyString())).thenReturn(vo);

        EsTermNormalizer termNormalizer = mock(EsTermNormalizer.class);
        when(termNormalizer.normalize("symptom", "咽痛"))
                .thenReturn(new EsTermNormalizer.NormalizeResult("咽喉痛", "中医症状词典", 3, null,
                        EsTermNormalizer.VIA_ES));
        when(termNormalizer.normalize("herb", "双花"))
                .thenReturn(new EsTermNormalizer.NormalizeResult("金银花", "中药词典", 1, "GS-001",
                        EsTermNormalizer.VIA_MEMORY));

        ResponseEntity<Result<NlpExtractVO>> resp =
                controller(client, termNormalizer).extract(dto("咽痛"));
        NlpExtractVO out = resp.getBody().getData();

        NlpExtractVO.Entity symptom = out.getSymptoms().get(0);
        assertEquals("咽喉痛", symptom.getContent());
        assertEquals("咽痛", symptom.getSourceText());
        assertEquals(Integer.valueOf(3), symptom.getNormLevel());
        assertEquals("中医症状词典", symptom.getNormSource());
        // 归一途径要原样透出：ES 索引命中与内存兜底命中必须可区分
        assertEquals(EsTermNormalizer.VIA_ES, symptom.getNormVia());

        NlpExtractVO.Herb herb = out.getHerbs().get(0);
        assertEquals("金银花", herb.getName());
        assertEquals("双花", herb.getSourceText());
        assertEquals(Integer.valueOf(1), herb.getNormLevel());
        assertEquals("GS-001", herb.getNormCode());
        assertEquals(EsTermNormalizer.VIA_MEMORY, herb.getNormVia());

        // 无词典字段：content 不动、不标命中级别、不带归一途径
        assertEquals("舌红", out.getTongueList().get(0).getContent());
        assertEquals("舌红", out.getTongueList().get(0).getSourceText());
        assertNull(out.getTongueList().get(0).getNormLevel());
        assertNull(out.getTongueList().get(0).getNormVia());
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
