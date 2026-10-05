package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CleanResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.DictionaryTermStore;
import com.tcm.ehr.service.IDictionaryFileService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清洗的两个口径（M3 / M7）。
 *
 * M3：常规路径**不能**回写 status/grade —— 那两列取自循环外的快照，清洗是同步长任务，
 * 期间并发的质控重算 / 人工复核会被静默回退。
 * M7：清洗改了参与哈希的四列后必须回写 text_hash —— 不回写则库里存的哈希与行内容永久不一致，
 * 导入的判重预筛（按内容现算）再也认不出该病历，同一份数据会重新进库。
 */
class GovernanceCleanContractTest {

    private Record cleanableRecord() {
        Record r = new Record();
        r.setId("r-1");
        r.setOrgId("org-A");
        r.setGrade("合格");
        r.setStatus("completed");
        // 核心文本非空 → 不触发「无法修复」隔离分支
        r.setChiefComplaint("头晕");
        // 带空白 → trim 后四列都变，哈希必然变化
        r.setGender(" 男 ");
        r.setPattern(" 肝郁 ");
        r.setTextHash("old-hash");
        return r;
    }

    private static com.baomidou.mybatisplus.extension.plugins.pagination.Page<Record> pageOf(List<Record> rows) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Record> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 1000);
        p.setRecords(rows);
        return p;
    }

    private GovernanceServiceImpl service(RecordMapper mapper) {
        GovernanceServiceImpl svc = new GovernanceServiceImpl(
                mock(EsTermNormalizer.class), new ObjectMapper(),
                mock(DictionaryTermStore.class),
                mock(ReviewTaskMapper.class));
        ReflectionTestUtils.setField(svc, "baseMapper", mapper);
        return svc;
    }

    @Test
    void cleanKeepsGradeAndRewritesTextHash() {
        RecordMapper mapper = mock(RecordMapper.class);
        // 批次12 · 12c：clean 由「整批 selectList」改为「惰性分页 selectPage + 单独 selectCount」。
        // ⚠️ 分页把「取完」定义为取到空页 ⇒ selectPage 要「第一次给一条、第二次给空页」；
        // 若每次都给同一条，迭代器永远认为还有数据，测试会死循环。
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(cleanableRecord())), pageOf(List.of()));

        CleanResultVO vo = service(mapper).clean(List.of("r-1"), null);

        // M3：常规路径不碰 status/grade；只有真隔离时才写
        verify(mapper, never()).updateCleanFields(anyString(), any(), any(), any(), any(), any(), any());
        // 字段确实被 trim 落库
        verify(mapper).updateCleanFieldsWithoutStatus(eq("r-1"), eq("男"), any(), eq("肝郁"), any());
        // M7：四列变了 → 必须回写新哈希，且不能仍是旧值
        verify(mapper).updateTextHash(eq("r-1"), anyString());
        verify(mapper, never()).updateTextHash(anyString(), eq("old-hash"));

        org.junit.jupiter.api.Assertions.assertEquals(1, vo.getTotal());
    }
}
