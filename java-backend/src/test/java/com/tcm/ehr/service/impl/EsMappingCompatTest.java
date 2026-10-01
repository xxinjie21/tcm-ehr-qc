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
 * <p>事故背景：批次 8b 引入按组织的 {@code org_id} 过滤后，从旧版本升级的索引是旧代码建的
 * （mapping 里没有 org_id）。{@code rebuild} 当时只看「索引是否存在」就复用，于是 bulk 写入的
 * {@code org_id} 被 ES <b>动态映射成 text</b>。而基础层的 org_id 是<b>空串</b>，
 * 空串在分词字段里不进倒排索引 —— {@code termsQuery("org_id","")} 永远查不到，
 * 全库归一显示「未收录」，且不报任何错。</p>
 *
 * <p>所以这条判据必须锁死：org_id 不是 keyword 就得重建索引。</p>
 */
class EsMappingCompatTest {

    private static Map<String, Object> props(Object orgIdDef) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("standard_term", Map.of("type", "keyword"));
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
    void keywordIsCompatible() {
        assertTrue(EsTermIndexServiceImpl.orgIdIsKeyword(props(Map.of("type", "keyword"))));
    }

    @Test
    @DisplayName("org_id 为 text（动态映射）：不兼容 —— 空串进不了倒排索引")
    void textIsIncompatible() {
        Map<String, Object> textWithSub = Map.of(
                "type", "text",
                "fields", Map.of("keyword", Map.of("type", "keyword", "ignore_above", 256)));
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(props(textWithSub)),
                "text 字段下空串基础层查不到，必须判为不兼容");
    }

    @Test
    @DisplayName("mapping 里没有 org_id（旧版本索引）：不兼容")
    void missingOrgIdIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(props(null)));
    }

    @Test
    @DisplayName("mapping 结构异常：不兼容（宁可重建，也不留坏索引）")
    void malformedMappingIsIncompatible() {
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(null));
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(Map.of()));
        assertFalse(EsTermIndexServiceImpl.orgIdIsKeyword(Map.of("properties", "not-a-map")));
    }
}
