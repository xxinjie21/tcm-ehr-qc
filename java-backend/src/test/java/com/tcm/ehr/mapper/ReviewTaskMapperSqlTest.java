package com.tcm.ehr.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ReviewTaskMapper} 注解 SQL 的机械守卫。
 *
 * <p><b>为什么需要它</b>：{@code upsertPending} 的 SQL 曾写成
 * {@code VALUES (REPLACE(UUID(), #{recordId}, …)} —— 括号不平衡，MySQL 直接报
 * {@code ERROR 1064}。而调用它的单测（{@code QcReviewDeadlineTest} 等）全部把 mapper
 * mock 掉了，**SQL 文本从没被任何断言碰过**，于是这条语法错误在仓库里躺了很久：
 * 表现是「评分落在待复核区间的病历建不出复核任务」，且只在真机跑质控时才暴露。</p>
 *
 * <p>本测试用反射把注解原文读出来做结构校验，把这类「注解 SQL 打错字」的缺陷拦在单测阶段。
 * 不做真库校验（单测不连库），只查三件机械事实：括号平衡、列数与值数一致、没有残留的
 * {@code REPLACE(} 误写。</p>
 */
class ReviewTaskMapperSqlTest {

    // ------------------------------------------------------------------ 断言

    @Test
    @DisplayName("upsertPending：括号平衡、列数与值数一致、无 REPLACE( 误写")
    void upsertPendingSqlIsWellFormed() throws Exception {
        String sql = sqlOf("upsertPending", String.class, String.class, Integer.class,
                String.class, LocalDateTime.class, LocalDateTime.class);
        // 去掉单引号字面量（'pending' / 'obsolete'），免得字面量里的括号干扰计数
        String bare = stripLiterals(sql);

        assertEquals(0, parenBalance(bare), "括号必须平衡：\n" + sql);
        assertFalse(bare.contains("REPLACE("),
                "VALUES 元组曾被误写成 REPLACE(UUID(), …)（括号不平衡、MySQL 报 1064）：\n" + sql);

        // 列清单与值元组必须一一对应 —— 这正是原缺陷的形态
        int colsAt = nextParen(bare, bare.indexOf("INSERT INTO review_tasks"));
        String cols = group(bare, colsAt);
        int valsAt = nextParen(bare, bare.indexOf("VALUES", colsAt));
        String vals = group(bare, valsAt);

        List<String> colList = splitTopLevel(cols);
        List<String> valList = splitTopLevel(vals);
        assertEquals(colList.size(), valList.size(),
                "列清单 " + colList.size() + " 项 vs 值 " + valList.size() + " 项，必须相等：\n" + sql);
        assertTrue(colList.contains("id"), "列清单应含 id：\n" + sql);
    }

    @Test
    @DisplayName("obsoleteActive：括号平衡")
    void obsoleteActiveSqlIsWellFormed() throws Exception {
        String sql = sqlOf("obsoleteActive", String.class, LocalDateTime.class);
        assertEquals(0, parenBalance(stripLiterals(sql)), "括号必须平衡：\n" + sql);
    }

    // ------------------------------------------------------------------ 工具

    private static String sqlOf(String methodName, Class<?>... params) throws Exception {
        Method m = ReviewTaskMapper.class.getMethod(methodName, params);
        Update u = m.getAnnotation(Update.class);
        assertNotNull(u, methodName + " 应带 @Update");
        return String.join("\n", u.value());
    }

    /** 把 {@code '...'} 字面量整体替换成 {@code ''}，只留结构 */
    private static String stripLiterals(String sql) {
        return sql.replaceAll("'[^']*'", "''");
    }

    /** 括号配平差值；中途出现多余的 {@code ')'} 直接返回 -1 */
    private static int parenBalance(String sql) {
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return depth;
    }

    /** 从 {@code from} 起找第一个 {@code '('} 的下标 */
    private static int nextParen(String sql, int from) {
        int i = sql.indexOf('(', Math.max(from, 0));
        assertTrue(i >= 0, "找不到 '('");
        return i;
    }

    /** 取 {@code from}（必须指向 {@code '('}）配对括号内的内容，支持嵌套 */
    private static String group(String sql, int from) {
        assertTrue(sql.charAt(from) == '(', "from 必须指向 '('");
        int depth = 0;
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(from + 1, i);
                }
            }
        }
        fail("括号未闭合：\n" + sql);
        return null;
    }

    /** 按顶层逗号切分（忽略嵌套括号内的逗号，如 {@code UUID()} 之后没有逗号但 {@code CONCAT(a, b)} 有） */
    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(body.substring(start).trim());
        return out;
    }
}
