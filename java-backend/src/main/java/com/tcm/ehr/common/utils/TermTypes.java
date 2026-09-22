package com.tcm.ehr.common.utils;

import java.util.Set;

/**
 * 术语类型清单（唯一副本）。
 *
 * <p>原先挂在 {@code DictionaryStore.TYPES} 上；那个类已随「归一不再用内存兜底」一并删除
 * （2026-09-23），清单迁到这里独立存放，避免又变成某个类的附属常量。</p>
 *
 * <p>与 {@link EntityNormalizer#dictionaryType(String)} 的分工：本类回答「系统有哪几类术语」，
 * 那个方法回答「structuredData 的哪个字段用哪类术语」。</p>
 */
public final class TermTypes {

    /** 全部术语类型；顺序即词典页与看板的展示顺序 */
    public static final Set<String> ALL = Set.of("disease", "pattern", "symptom", "herb", "formula");

    private TermTypes() {
    }
}
