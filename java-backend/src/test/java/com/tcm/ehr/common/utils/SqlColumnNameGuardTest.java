package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织相关列名 / 表名的**全仓**守卫（批次 6 补）。
 *
 * <p>为什么需要这条：批次 4 把 DB 列名 {@code group_id → org_id}、表名
 * {@code research_groups/group_members → organizations/organization_members}，
 * 批次 5 的改名只覆盖了 <b>Java 标识符</b>；而 {@code QueryWrapper.eq("group_id", …)}、
 * {@code @Select("… WHERE group_id = …")} 这类 <b>SQL 字符串字面量</b>不会被替换 ——
 * 结果每个带组织数据域的查询在运行期都会 {@code Unknown column}，
 * 但单测断言的正是同一个陈旧字面量（{@code sql.contains("group_id")}），
 * <b>所以测试照样全绿</b>。这类缺陷测试天然测不出，只能靠「扫全仓」兜住。</p>
 */
class SqlColumnNameGuardTest {

    private static final Path SRC = Path.of("src/main/java");

    /** 已被淘汰的组织相关列名 / 表名：出现在源码里就是漏改 */
    private static final Pattern STALE = Pattern.compile(
            "\\b(group_id|research_groups|group_members)\\b");

    /**
     * 只扫会被执行的代码；跳过注释与文档注释（{@code * } / {@code //} 开头的行），
     * 否则说明性文字里的历史说明会被误判。
     */
    private static boolean isComment(String line) {
        String t = line.strip();
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
    }

    @Test
    void noStaleGroupColumnOrTableNamesInExecutableCode() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isComment(line)) {
                        continue;
                    }
                    Matcher m = STALE.matcher(line);
                    if (m.find()) {
                        hits.add(f.getFileName() + ":" + (i + 1) + " → " + line.strip());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "源码里仍有已废弃的组织列名/表名（批次 4 已把 DB 改成 org_id / organizations，"
                        + "Java 标识符改名不覆盖 SQL 字符串字面量，运行期会 Unknown column）：\n  "
                        + String.join("\n  ", hits));
    }
}