package com.tcm.ehr.domain.po;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 实体字段 → 列名的映射守卫。
 *
 * <p>批次 8b 出过一次真实事故：{@code DictionaryTerm} 把别名列写成
 * {@code aliasesJson}，而批次 4 建的列叫 {@code aliases}。后果不是编译失败，
 * 而是<b>服务照常启动</b>（启动器按类型逐个 catch）、只有词典加载抛
 * {@code Unknown column}、术语归一整体落空 —— 报错出现在离改动很远的地方。</p>
 *
 * <p>本测试用<b>硬编码的真实列名清单</b>（来自 {@code SHOW COLUMNS FROM ...}）
 * 反查实体：清单改了就必须同步改这里，从而强制每次改字段都看一眼表结构。
 * 它替代不了真实连库，但能在提交前把「照着印象写列名」这类错误挡住。</p>
 */
class EntityColumnMappingTest {

    /** dictionary_terms 的真实列（批次 4 建表） */
    private static final Set<String> DICTIONARY_TERMS_COLUMNS = Set.of(
            "id", "org_id", "type", "standard_term", "code", "source", "aliases",
            "create_time", "update_time");

    @Test
    @DisplayName("DictionaryTerm 必须映射到 aliases —— 事故回归点")
    void dictionaryTermUsesAliasesColumn() {
        Set<String> mapped = mappedColumns(DictionaryTerm.class);
        assertTrue(mapped.contains("aliases"),
                "DictionaryTerm 应映射到 aliases 列，实际映射到: " + mapped);
        assertTrue(!mapped.contains("aliases_json"),
                "aliases_json 是批次 8b 的事故列名，表里并不存在");
    }

    private static void assertNoUnknownColumn(Class<?> type, Set<String> realColumns) {
        Set<String> mapped = mappedColumns(type);
        for (String col : mapped) {
            assertTrue(realColumns.contains(col),
                    type.getSimpleName() + " 的字段映射到列 " + col
                            + "，但 " + type.getSimpleName() + " 对应的表里没有这一列。"
                            + " 真实列: " + realColumns);
        }
    }

    /** 反射取所有非静态字段，按 MyBatis-Plus 的驼峰转下划线规则算出列名 */
    private static Set<String> mappedColumns(Class<?> type) {
        Set<String> out = new HashSet<>();
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            // 显式 @TableField 优先
            com.baomidou.mybatisplus.annotation.TableField tf =
                    f.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);
            if (tf != null && !tf.value().isEmpty()) {
                out.add(tf.value());
                continue;
            }
            out.add(toSnake(f.getName()));
        }
        assertNotNull(type.getName());
        return out;
    }

    /** MyBatis-Plus 默认驼峰转下划线：小写字母之间的大写字母前插入下划线 */
    private static String toSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
