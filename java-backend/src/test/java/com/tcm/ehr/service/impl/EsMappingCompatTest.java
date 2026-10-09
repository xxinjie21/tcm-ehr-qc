package com.tcm.ehr.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「索引结构是否兼容」的回归测试。
 *
 * 事故背景（批次 8b）：引入按组织的 org_id 过滤后，从旧版本升级的索引是旧代码建的
 * （mapping 里没有 org_id）。rebuild 当时只看「索引是否存在」就复用，于是 bulk 写入的
 * org_id 被 ES 动态映射成 text。而基础层的 org_id 是空串，空串在分词字段里不进倒排索引
 * —— termsQuery("org_id","") 永远查不到，全库归一显示「未收录」，且不报任何错。
 *
 * 结论：判据 = org_id 是 keyword 才算兼容。这条不能放松，
 * 放松的后果都是「不报错、只是某个功能静默失效」。
 */
class EsMappingCompatTest {

    private static final Map<String, Object> KEYWORD = Map.of("type", "keyword");

    private static final Map<String, Object> TEXT_WITH_SUB =
            Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword")));

    /** 组一份 mapping 源；orgIdDef 传 null 表示该字段缺失 */
    private static Map<String, Object> mapping(Map<String, Object> orgIdDef) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("standard_term", KEYWORD);
        if (orgIdDef != null) {
            properties.put("org_id", orgIdDef);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("_meta", Map.of("version", "abc"));
        source.put("properties", properties);
        return source;
    }

    @Test
    @DisplayName("org_id 为 keyword：兼容")
    void orgIdKeywordIsCompatible() {
        assertTrue(EsTermIndexServiceImpl.orgIdIsKeyword(mapping(KEYWORD)));
    }

    @Test
    @DisplayName("org_id 为 text（动态映射）：不兼容 —— 空串进不了倒排索引")
    void textOrgIdIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(mapping(TEXT_WITH_SUB)),
                "text 字段下空串基础层查不到，必须判为不兼容");
    }

    @Test
    @DisplayName("mapping 里没有 org_id（旧版本索引）：不兼容")
    void missingOrgIdIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(mapping(null)));
    }

    @Test
    @DisplayName("mapping 结构异常：不兼容（宁可重建，也不留坏索引）")
    void malformedMappingIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(null));
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(Map.of()));
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(Map.of("properties", "not-a-map")));
    }
}