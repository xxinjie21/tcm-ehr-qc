package com.tcm.ehr.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES 索引服务组织级的两条纯函数契约。
 *
 * <p>这里不启 ES（那要真集群），只锁「<b>组织维度参与了 id 计算与查询过滤</b>」这个事实 ——
 * 它是「A 组织的词不会串到 B 组织」的全部凭据，一旦被改回只按标准词算 id，
 * 同名词就会互相覆盖，而这种 bug 在功能测试里表现为「改了词典没生效」，极难定位。</p>
 */
class EsOrgKeyTest {

    @Test
    @DisplayName("同名标准词、不同组织：文档 id 必须不同（否则互相覆盖）")
    void sameTermDifferentOrgGetsDifferentId() throws Exception {
        // 反射调用私有 hashId：它是要守护的核心，且没有更小的可测面
        java.lang.reflect.Method hashId = null;
        for (java.lang.reflect.Method m : EsTermIndexServiceImpl.class.getDeclaredMethods()) {
            if (m.getName().equals("hashId") && m.getParameterCount() == 2) {
                hashId = m;
            }
        }
        assertTrue(hashId != null, "应存在双参 hashId(orgId, standardTerm)");

        hashId.setAccessible(true);
        String idA = (String) hashId.invoke(new EsTermIndexServiceImpl(null, null), "org-A", "甘草");
        String idBase = (String) hashId.invoke(new EsTermIndexServiceImpl(null, null), "", "甘草");
        String idA2 = (String) hashId.invoke(new EsTermIndexServiceImpl(null, null), "org-A", "甘草");
        String idB = (String) hashId.invoke(new EsTermIndexServiceImpl(null, null), "org-B", "甘草");

        assertNotEquals(idA, idBase, "组织词与基础层同名词必须是两个文档");
        assertNotEquals(idA, idB, "两个组织的同名词必须是两个文档");
        assertEquals(idA, idA2, "同组织同词必须稳定（重复导入是覆盖而非新增）");
    }

    @Test
    @DisplayName("召回查询：org_id 过滤为「基础层 + 当前组织」，既不漏基础层也不越界")
    void recallQueryFiltersByOrgScope() throws Exception {
        EsTermIndexServiceImpl svc = new EsTermIndexServiceImpl(null, null);
        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method x : EsTermIndexServiceImpl.class.getDeclaredMethods()) {
            if (x.getName().equals("recallQuery") && x.getParameterCount() == 2) {
                m = x;
            }
        }
        assertTrue(m != null, "应存在 recallQuery(orgId, input)");
        m.setAccessible(true);
        Object q = m.invoke(svc, "org-A", "甘草");
        String json = q.toString();
        // org_id 过滤必须同时含基础层空串与当前组织
        assertTrue(json.contains("org_id"), "查询必须带 org_id 过滤，实际: " + json);
        assertTrue(json.contains("org-A"), "查询必须含当前组织，实际: " + json);
    }

    @Test
    @DisplayName("mapping 声明 org_id 为 keyword（不加则无法按组织过滤）")
    void mappingDeclaresOrgIdAsKeyword() throws Exception {
        EsTermIndexServiceImpl svc = new EsTermIndexServiceImpl(null, null);
        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method x : EsTermIndexServiceImpl.class.getDeclaredMethods()) {
            if (x.getName().equals("mappingProperties")) {
                m = x;
            }
        }
        assertTrue(m != null);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) m.invoke(svc);
        assertTrue(props.containsKey("org_id"), "mapping 必须声明 org_id");
        assertEquals("keyword", ((Map<?, ?>) props.get("org_id")).get("type"));
    }



}
