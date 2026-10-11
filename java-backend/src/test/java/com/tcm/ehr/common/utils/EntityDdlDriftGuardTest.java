package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体字段 ↔ 建表脚本的**漂移**守卫。
 *
 * <p>为什么需要这条：{@code database-init.sql} 是唯一的 schema 来源（全仓没有 Flyway/Liquibase
 * 之类的自动迁移，{@code DataInitializationListener} 只探活 Redis/ES 与对账词典，不建表不改表）。
 * 于是「改了实体、忘了改建表脚本」不会让任何测试变红 —— 单测要么全 mock
 * （{@code QcBatchServiceImplTest}），要么跑 H2 精简 schema（{@code schema-h2.sql} 只有 7 张表）。
 * 真机上直到某个查询按显式列清单发 SQL 才炸 {@code Unknown column}。</p>
 *
 * <p>本类就是这个缺陷的实例：{@code 36dba403} 给 {@code QcBatchDTO/QcTask} 加了幂等键
 * {@code requestKey}，却没动 {@code database-init.sql}；批次 26 收敛时它又被上移到
 * {@code AbstractBatchTask}，连 {@code nlp_task} 的差异一起抹平 —— 结果 {@code qc_task}
 * 的每一次 {@code selectById} 都 1054，整个批重算不可用。</p>
 *
 * <p>断言方向是单向的：实体有、DDL 没有 ⇒ 失败。DDL 多出来的冗余列（如
 * {@code dictionary_terms.create_time}）是允许的。</p>
 */
class EntityDdlDriftGuardTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS\\s+(\\w+)\\s*\\(([\\s\\S]*?)\\n\\)\\s*ENGINE");

    /** 建表脚本里的非列行首关键字 */
    private static final Set<String> NON_COLUMN_KEYWORDS = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL", "CHECK");

    @Test
    void everyPersistentEntityFieldExistsInCreateTableScript() throws IOException, ClassNotFoundException {
        Path ddl = findDatabaseInitSql();
        String script = Files.readString(ddl, StandardCharsets.UTF_8);

        Map<String, Set<String>> ddlColumns = new LinkedHashMap<>();
        Matcher m = CREATE_TABLE.matcher(script);
        while (m.find()) {
            ddlColumns.put(m.group(1).toLowerCase(), parseColumns(m.group(2)));
        }

        List<String> drift = new ArrayList<>();
        for (Class<?> entity : entityClasses()) {
            TableName tn = entity.getAnnotation(TableName.class);
            String table = tn.value().toLowerCase();
            Set<String> columns = ddlColumns.get(table);
            if (columns == null) {
                drift.add(entity.getSimpleName() + " → 建表脚本里没有表 " + table);
                continue;
            }
            for (String expected : persistentColumns(entity)) {
                if (!columns.contains(expected)) {
                    drift.add(table + " 缺列 " + expected + "（" + entity.getSimpleName() + " 的字段）");
                }
            }
        }

        assertTrue(drift.isEmpty(),
                "实体字段在 " + ddl.getFileName() + " 里没有对应列。"
                        + "schema 只能人工执行该脚本，漏列不会让任何 mock 单测变红，"
                        + "真机上会 Unknown column：\n  " + String.join("\n  ", drift));
    }

    // ------------------------------------------------------------------ 实体侧

    /** 扫 src/main/java 找出全部带 @TableName 的实体类（测试运行时编译产物已存在，可反射） */
    private static List<Class<?>> entityClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (!src.contains("@TableName")) {
                    continue;
                }
                Matcher pkg = Pattern.compile("package\\s+([\\w.]+);").matcher(src);
                if (!pkg.find()) {
                    continue;
                }
                String simple = f.getFileName().toString().replace(".java", "");
                Class<?> c = Class.forName(pkg.group(1) + "." + simple);
                if (c.isAnnotationPresent(TableName.class)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** 实体（含父类）的持久化列名：跳过静态/瞬态字段与 @TableField(exist = false) */
    private static Set<String> persistentColumns(Class<?> entity) {
        Set<String> cols = new TreeSet<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                TableField tf = f.getAnnotation(TableField.class);
                if (tf != null && !tf.exist()) {
                    continue;
                }
                cols.add(camelToSnake(f.getName()));
            }
        }
        return cols;
    }

    /** 与 MyBatis-Plus 默认 map-underscore-to-camel-case 互为逆运算 */
    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    // ------------------------------------------------------------------ DDL 侧

    private static Set<String> parseColumns(String tableBody) {
        Set<String> cols = new LinkedHashSet<>();
        for (String raw : tableBody.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            String first = line.split("\\s+")[0].replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
            if (first.isEmpty() || NON_COLUMN_KEYWORDS.contains(first.toUpperCase())) {
                continue;
            }
            cols.add(first.toLowerCase());
        }
        return cols;
    }

    /** 建表脚本在仓库根，不在模块内 —— 从工作目录逐级上找 */
    private static Path findDatabaseInitSql() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("database-init.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 database-init.sql（从 " + Path.of("").toAbsolutePath() + " 逐级上找）");
    }
}
