package com.tcm.ehr.dictionary;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存词典缓存：type(disease/pattern/symptom/herb/formula) -> 术语列表
 * 启动时从JSON词典文件加载；导入/回滚后刷新
 */
@Component
public class DictionaryStore {

    public static final Set<String> TYPES = Set.of("disease", "pattern", "symptom", "herb", "formula");

    private final Map<String, List<TermEntry>> cache = new ConcurrentHashMap<>();

    public void put(String type, List<TermEntry> entries) {
        cache.put(type, Collections.unmodifiableList(new ArrayList<>(entries)));
    }

    public List<TermEntry> get(String type) {
        return cache.getOrDefault(type, Collections.emptyList());
    }

    public int size(String type) {
        return get(type).size();
    }

    public boolean isLoaded() {
        return TYPES.stream().allMatch(t -> cache.containsKey(t));
    }
}
