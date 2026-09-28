package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.vo.ImportStatusVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 有界导入任务表（批3·B3-4）。
 *
 * <p>原来是上不封顶的 {@code ConcurrentHashMap}，而全文件没有任何 {@code remove} ——
 * 每次导入新增一条（含失败明细），长期运行内存持续增长。这里锁住「有界」与
 * 「淘汰最早的」两条：只断言大小不够，还得确认留下的是最近的那几条，否则查进度会查不到刚导入的任务。</p>
 *
 * <p>「接诊时间」归一：导入源这一列是 14 位紧凑数字串（{@code 20221224090613}），
 * 单元格类型是数值、格式为 General —— 既不是日期格式也不含分隔符，只按
 * {@code yyyy-MM-dd HH:mm:ss} 硬解析会整列落到 null（列表接诊时间全空）。</p>
 */
class RecordServiceImplTest {

    /** 与 RecordServiceImpl.DT 同形；断言归一结果必须能被它解析，否则等于白归一 */
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void taskStoreKeepsOnlyTheNewestEntries() {
        Map<String, ImportStatusVO> store = RecordServiceImpl.newTaskStore(3);

        for (int i = 1; i <= 5; i++) {
            ImportStatusVO vo = new ImportStatusVO();
            vo.setTaskId("task-" + i);
            store.put("task-" + i, vo);
        }

        assertEquals(3, store.size(), "超过上限必须淘汰，否则就是内存泄漏");
        assertNull(store.get("task-1"), "最早的应被淘汰");
        assertNull(store.get("task-2"), "最早的应被淘汰");
        assertNotNull(store.get("task-5"), "最近的要留着 —— 导入后还得能回看进度");
    }

    @Test
    void compactNumericDateTimeIsNormalized() {
        // 导入源的真实格式：14 位到秒 / 12 位到分 / 8 位到日
        assertNormalized("20221224090613", "2022-12-24 09:06:13");
        assertNormalized("202212240906", "2022-12-24 09:06:00");
        assertNormalized("20221224", "2022-12-24 00:00:00");
    }

    @Test
    void chineseAndSeparatedDateFormsAreNormalized() {
        assertNormalized("2022年12月24日", "2022-12-24 00:00:00");
        assertNormalized("2022年12月24日 09:06", "2022-12-24 09:06:00");
        assertNormalized("2022/12/24", "2022-12-24 00:00:00");
        assertNormalized("2022/12/24 09:06:13", "2022-12-24 09:06:13");
        assertNormalized("2022.12.24 09:06:13", "2022-12-24 09:06:13");
    }

    @Test
    void singleDigitMonthDayHourArePadded() {
        assertNormalized("2022-1-2", "2022-01-02 00:00:00");
        assertNormalized("2022-1-2 9:6", "2022-01-02 09:06:00");
        assertNormalized("2022/1/2 9:6:7", "2022-01-02 09:06:07");
    }

    @Test
    void standardFormsStillWork() {
        // 回归：原实现支持的三种长度，改归一后必须仍然成立
        assertNormalized("2022-12-24", "2022-12-24 00:00:00");
        assertNormalized("2022-12-24 09:06:13", "2022-12-24 09:06:13");
        assertNormalized("2022-12-24T09:06:13", "2022-12-24 09:06:13");
        assertNormalized("2022-12-24 09:06:13.123", "2022-12-24 09:06:13");
        assertNormalized("2022-12-24 09:06:13+08:00", "2022-12-24 09:06:13");
    }

    @Test
    void unparsableValueFailsParseInsteadOfGuessing() {
        // 认不出来时原样返回，由调用侧的 parse 抛错并落到 null；
        // 关键是「不能悄悄给出一个错误的时间」——那比缺接诊时间更难发现
        String got = RecordServiceImpl.normalizeDateTime("不详");
        assertThrows(DateTimeParseException.class, () -> LocalDateTime.parse(got, DT),
                "认不出来的值必须让 parse 抛错，不能归一成一个看似合法的时间");
    }

    /** 归一结果既要等于期望串，也要能被标准格式解析（只对字符串不够，白归一也会通过） */
    private static void assertNormalized(String raw, String expected) {
        String got = RecordServiceImpl.normalizeDateTime(raw);
        assertEquals(expected, got, "归一结果不对：" + raw);
        LocalDateTime.parse(got, DT);
    }
}
