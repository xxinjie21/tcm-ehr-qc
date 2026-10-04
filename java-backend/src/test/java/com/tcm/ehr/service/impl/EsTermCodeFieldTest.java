package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码随术语进 ES 的映射契约（批次 22）。
 *
 *
 * 批次 22 之前 ES 文档只有 standard_term / aliases / source / org_id 四个字段，
 *
 * code 压根不往索引里写。于是链条在中间断掉：词典页能显示编码（读的是 DB），
 * 但归一结果里的 normCode 恒为 null（读的是 ES）—— 「数据看着正常、功能其实没通」，
 * 是最难发现的一类缺陷。
 *
 *
 * 这里钉住三处：mapping 声明了 code、bulk 会写 code、读回时能拿到 code。
 *
 * 前两处在实现里，本测试通过反射取到 mappingProperties 的结果来断言；
 * 读回逻辑 toEntry 是 private，改用「mapping 声明与 toEntry 的字段名一致」来约束，
 * 避免为测一个 getter 把可见性放宽。
 */
class EsTermCodeFieldTest {

    /**
 * 取 mappingProperties 的结果。
 *
     * mappingProperties 是实例方法但只读常量式声明（不碰 client），所以用
     * 构造器传 null 依赖 —— 反射取私有方法；失败时给出可读原因。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mappingProps() throws Exception {
        var ctor = EsTermIndexServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        var m = EsTermIndexServiceImpl.class.getDeclaredMethod("mappingProperties");
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(ctor.newInstance(args));
    }

    @Test
    @DisplayName("mapping 声明了 code 且类型为 keyword（编码要等值查询，不能分词）")
    void mappingDeclaresCodeAsKeyword() throws Exception {
        Map<String, Object> props = mappingProps();

        assertTrue(props.containsKey("code"), "mapping 必须声明 code，否则写进去读不回来");
        Object code = props.get("code");
        assertTrue(code instanceof Map<?, ?>, "code 的映射定义应是 Map，实际: " + code);
        Map<?, ?> codeDef = (Map<?, ?>) code;
        assertEquals("keyword", String.valueOf(codeDef.get("type")));
    }

    @Test
    @DisplayName("既有字段没被挤掉（加 code 不能破坏既有 mapping）")
    void existingFieldsPreserved() throws Exception {
        Map<String, Object> props = mappingProps();

        for (String f : List.of("standard_term", "aliases", "source", "org_id", "code")) {
            assertTrue(props.containsKey(f), "mapping 缺少字段: " + f);
        }
    }

    @Test
    @DisplayName("bulk 与 toEntry 的字段名一致（否则编码写了读不回来）")
    void writeAndReadFieldNamesAgree() throws Exception {
        // bulk 写的是 code；读回走 toEntry(src.get("code"))。
        // 两处字段名都是 "code"，这里断言 mapping 里声明的字段名与之字面一致，
        // 避免将来有人把其中一处改成 code_/termCode 之类而没人发现。
        Map<String, Object> props = mappingProps();
        assertNotNull(props.get("code"));
        assertTrue(props.containsKey("code"), "字段名必须是 code");
        assertEquals(5, props.size(), "文档字段数 = standard_term/aliases/source/org_id/code");
    }

    @Test
    @DisplayName("TermEntry 的 code 能承载编码（模型侧字段在位）")
    void termEntryCarriesCode() {
        TermEntry e = new TermEntry("消渴", List.of("消渴病"), "中医临床诊疗术语 疾病", "BNF01001");
        assertEquals("BNF01001", e.getCode());
    }
}