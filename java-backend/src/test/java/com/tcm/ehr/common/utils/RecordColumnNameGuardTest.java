package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * records 表 {@code QueryWrapper.select("...")} 列名守卫（性能审查 A2/A3 落地补）。
 *
 * <p>为什么需要：列表 / 统计 / 批处理为省全表 I/O 新加了 {@code .select("id", "grade", …)}
 * 这类列名**字符串字面量**（RecordServiceImpl.searchRecords、StatsServiceImpl.recordsFor、
 * QcServiceImpl.deductionStats 等）。Java 编译只认它是不是字符串，不认是不是列名 ——
 * 写错一个就是「测试全绿、运行期 {@code Unknown column 'xxx' }」。这类缺陷只能靠
 * 「扫源码里 .select( 的每一个引号 token，逐一比对真实列清单」兜住（范式同
 * {@link SqlColumnNameGuardTest}）。</p>
 *
 * <p>只扫 {@code QueryWrapper<Record>} 相关文件：其它表（review_tasks 等）有自己的列，
 * 不在此清单内。新增 records 列时请同步维护 {@link #RECORDS_COLUMNS}。</p>
 */
class RecordColumnNameGuardTest {

    /** tcm_ehr.records 的真实列清单（SHOW COLUMNS 采集，2026-10-08） */
    private static final Set<String> RECORDS_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "registration_no", "outpatient_no", "gender", "age", "visit_count",
            "western_diagnosis", "tcm_diagnosis", "present_illness", "chief_complaint",
            "self_report", "inspection", "pulse", "tongue", "physical_exam", "pattern",
            "prescription", "follow_up", "treatment_effect", "department", "doctor_id",
            "visit_time", "structured_data", "qc_results", "score", "grade", "status",
            "create_time", "update_time", "governed", "org_id", "text_hash",
            "manually_edited"));

    private static final Path SRC = Path.of("src/main/java");

    /** 命中 `QueryWrapper<Record>` 的文件（只在这些文件里做 select 列名校验） */
    private static final List<String> RECORD_WRAPPER_FILES = List.of(
            "RecordServiceImpl.java",
            "StatsServiceImpl.java",
            "QcServiceImpl.java",
            "QcBatchServiceImpl.java",
            "RecordDeleter.java",
            "RecordFilter.java");

    private static final Pattern SELECT_CALL = Pattern.compile("\\.select\\(([^)]*)\\)");
    private static final Pattern TOKEN = Pattern.compile("\"([^\"]+)\"");

    private static boolean isComment(String line) {
        String t = line.strip();
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
    }

    @Test
    void everyQueryWrapperSelectColumnExistsInRecordsTable() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!RECORD_WRAPPER_FILES.contains(f.getFileName().toString())) {
                    continue;
                }
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isComment(line)) {
                        continue;
                    }
                    Matcher m = SELECT_CALL.matcher(line);
                    while (m.find()) {
                        Matcher t = TOKEN.matcher(m.group(1));
                        while (t.find()) {
                            String col = t.group(1);
                            if (!RECORDS_COLUMNS.contains(col)) {
                                hits.add(f.getFileName() + ":" + (i + 1) + " → .select(\"" + col + "\")");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "QueryWrapper<Record>.select() 里的列名在 records 表中不存在（运行期会 Unknown column）：\n  "
                        + String.join("\n  ", hits));
    }
}