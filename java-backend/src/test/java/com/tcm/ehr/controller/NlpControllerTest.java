package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.domain.dto.NlpExtractDTO;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批G·8.1：NlpController 契约 —— 空文本 400；服务不可用降级为空 9 类（modelAvailable=false）。
 */
class NlpControllerTest {

    private NlpExtractDTO dto(String text) {
        NlpExtractDTO d = new NlpExtractDTO();
        d.setText(text);
        return d;
    }

    @Test
    void blankTextReturns400() {
        NlpController controller = new NlpController(mock(PythonNlpClient.class));
        assertEquals(400, controller.extract(dto("   ")).getStatusCode().value());
        assertEquals(400, controller.extract(new NlpExtractDTO()).getStatusCode().value());
    }

    @Test
    void unavailableDegradesToEmptyVo() {
        PythonNlpClient client = mock(PythonNlpClient.class);
        when(client.extract(anyString())).thenReturn(null);
        ResponseEntity<Result<NlpExtractVO>> resp = new NlpController(client).extract(dto("发热"));
        assertEquals(200, resp.getStatusCode().value());
        NlpExtractVO vo = resp.getBody().getData();
        assertFalse(vo.isModelAvailable());
        assertEquals(0, vo.getDiseases().size());
        assertEquals(0, vo.getHerbs().size());
    }
}
