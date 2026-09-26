package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.EntityTypes;

import java.util.Set;

/**
 * 术语类型清单（唯一副本）。
 *
 * <p> 起改为直接取 {@link EntityTypes} 中 {@code dict=true} 的类型（疾病/证候/症状/中药/方剂），
 * 不再单独维护，避免与"实体类型目录"漂移。</p>
 *
 * <p>与 {@link EntityNormalizer#dictionaryType(String)} 的分工：本类回答「系统有哪几类术语」，
 * 那个方法回答「structuredData 的哪个字段用哪类术语」。</p>
 */
public final class TermTypes {

    /** 全部术语类型（顺序即词典页展示顺序） */
    public static final Set<String> ALL = EntityTypes.dictKeys();

    private TermTypes() {
    }
}
