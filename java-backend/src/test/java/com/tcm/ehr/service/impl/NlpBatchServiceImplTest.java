package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.dto.FiltersDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量解析「提交期」的序列化契约（批1·B1-3）。
 *
 * <p>锁的是一条静默降级：筛选条件序列化失败若被吞成字符串 {@code "null"}，
 * {@code readFilters} 会把它读成 {@code null}，任务就从「指定范围」变成<b>全库扫描</b>——
 * 用户以为只跑了筛出来的那几百条。所以提交路径必须失败即抛。</p>
 *
 * <p>放在 {@code service.impl} 包内，是为了直接测包级可见的
 * {@link NlpBatchServiceImpl#writeJsonStrict}；走 {@code submit()} 测不了这条 ——
 * 入参 {@code NlpBatchDTO.filters} 的类型是 {@code FiltersDTO}，构造不出不可序列化的值。</p>
 */
class NlpBatchServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** null 不是失败：序列化成 {@code "null"}、读回为 null，语义是「不限范围」 */
    @Test
    void nullFiltersSerializeToJsonNull() {
        assertEquals("null", NlpBatchServiceImpl.writeJsonStrict(mapper, null));
    }

    @Test
    void normalFiltersSerialize() {
        FiltersDTO f = new FiltersDTO();
        f.setDepartment("中医内科");
        f.setDateRange(List.of("2026-01-01", "2026-01-31"));

        String json = NlpBatchServiceImpl.writeJsonStrict(mapper, f);

        assertTrue(json.contains("中医内科"), json);
        assertTrue(json.contains("2026-01-01"), json);
    }

    /** 不可序列化 → 抛异常中止提交，绝不返回 "null"（那会被读成「不限范围」） */
    @Test
    void unserializableFiltersThrowInsteadOfDegradingToNull() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NlpBatchServiceImpl.writeJsonStrict(mapper, new Boom()));

        assertTrue(e.getMessage().contains("无法序列化"), e.getMessage());
    }

    /** 取值即抛的 bean：Jackson 序列化它必然失败 */
    public static class Boom {
        @SuppressWarnings("unused")
        public String getExplode() {
            throw new IllegalStateException("boom");
        }
    }
}
