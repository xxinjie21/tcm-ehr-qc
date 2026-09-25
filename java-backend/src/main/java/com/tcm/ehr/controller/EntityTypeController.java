package com.tcm.ehr.controller;

import com.tcm.ehr.common.config.EntityTypes;
import com.tcm.ehr.common.domain.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体类型目录（批S）：结构化解析 / 术语词典 / 质控规则 的单一来源，供前端渲染分类与下拉。
 * 【权限：登录即可】
 */
@RestController
public class EntityTypeController {

    @GetMapping("/api/entity-types")
    public Result<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = EntityTypes.all().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", t.key());
            m.put("label", t.label());
            m.put("structuredKey", t.structuredKey());
            m.put("dict", t.dict());
            m.put("fallback", t.fallback());
            m.put("order", t.order());
            return m;
        }).toList();
        return Result.ok(out);
    }
}
