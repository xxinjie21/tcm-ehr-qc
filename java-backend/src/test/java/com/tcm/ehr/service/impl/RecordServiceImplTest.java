package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.vo.ImportStatusVO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 有界导入任务表（批3·B3-4）。
 *
 * <p>原来是上不封顶的 {@code ConcurrentHashMap}，而全文件没有任何 {@code remove} ——
 * 每次导入新增一条（含失败明细），长期运行内存持续增长。这里锁住「有界」与
 * 「淘汰最早的」两条：只断言大小不够，还得确认留下的是最近的那几条，否则查进度会查不到刚导入的任务。</p>
 */
class RecordServiceImplTest {

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
}
