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
 *
 * 事故背景一（批次 8b）：引入按组织的 org_id 过滤后，从旧版本升级的索引是旧代码建的
 *
 * （mapping 里没有 org_id）。rebuild 当时只看「索引是否存在」就复用，于是 bulk 写入的
 * org_id 被 ES 动态映射成 text。而基础层的 org_id 是空串，空串在分词字段里不进倒排索引
 * —— termsQuery("org_id","") 永远查不到，全库归一显示「未收录」，且不报任何错。
 *
 *
 * 事故背景二（批次 22）：判据只查 org_id，于是给 mapping 补上 code 之后，
 *
 * 旧索引仍被判为「兼容」而跳过重建 —— 现象是「词典页能看到编码，但归一结果里没有
 * normCode」，即编码链路看着正常、实际没通。所以 code 也必须纳入判据。
 *
 *
 * 结论：判据 = org_id 与 code 都是 keyword 才算兼容。这条不能放松，
 *
 * 放松的后果都是「不报错、只是某个功能静默失效」。
 */
class EsMappingCompatTest {

    private static final Map<String, Object> KEYWORD = Map.of("type", "keyword");

    private static final Map<String, Object> TEXT_WITH_SUB =
            Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword")));

    /** 组一份 mapping 源；orgIdDef / codeDef 传 null 表示该字段缺失 */
    private static Map<String, Object> mapping(Map<String, Object> orgIdDef, Map<String, Object> codeDef) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("standard_term", KEYWORD);
        if (orgIdDef != null) {
            properties.put("org_id", orgIdDef);
        }
        if (codeDef != null) {
            properties.put("code", codeDef);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("_meta", Map.of("version", "abc"));
        source.put("properties", properties);
        return source;
    }

    /** 只放 org_id、不放 code —— 代表批次 22 之前建的索引 */
    private static Map<String, Object> legacyWithoutCode(Map<String, Object> orgIdDef) {
        return mapping(orgIdDef, null);
    }

    @Test
    @DisplayName("org_id 与 code 都是 keyword：兼容")
    void bothKeywordIsCompatible() {
        assertTrue(EsTermIndexServiceImpl.orgIdIsKeyword(mapping(KEYWORD, KEYWORD)));
    }

    @Test
    @DisplayName("org_id 为 text（动态映射）：不兼容 —— 空串进不了倒排索引")
    void textOrgIdIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(mapping(TEXT_WITH_SUB, KEYWORD)),
                "text 字段下空串基础层查不到，必须判为不兼容");
    }

    @Test
    @DisplayName("mapping 里没有 org_id（旧版本索引）：不兼容")
    void missingOrgIdIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(mapping(null, KEYWORD)));
    }

    @Test
    @DisplayName("mapping 里没有 code（批次 22 之前的索引）：不兼容，否则编码灌不进去")
    void missingCodeIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(legacyWithoutCode(KEYWORD)),
                "只有 org_id 没有 code 时必须判为不兼容并重建 —— "
                        + "否则会出现「词典页有编码、归一结果没有 normCode」");
    }

    @Test
    @DisplayName("code 为 text：同样不兼容（编码要等值查询，不能分词）")
    void codeMustBeKeyword() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(mapping(KEYWORD, TEXT_WITH_SUB)));
    }

    @Test
    @DisplayName("mapping 结构异常：不兼容（宁可重建，也不留坏索引）")
    void malformedMappingIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(null));
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(Map.of()));
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(Map.of("properties", "not-a-map")));
    }
}