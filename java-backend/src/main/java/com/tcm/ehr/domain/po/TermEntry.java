package com.tcm.ehr.domain.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 术语条目（词典文件与ES文档的统一模型）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TermEntry {

    /** 标准术语 */
    private String standardTerm;

    /** 别名列表（全量入ES，用于模糊归一） */
    private List<String> aliases = new ArrayList<>();

    /** 来源标准（如：中国药典2025年版） */
    private String source = "";

    /** 可选国标代码（GB/T 15657 / GB/T 16751 等）；未收录为 null，不入 ES 索引 */
    private String code;

    /** 兼容原三参调用（国标代码缺省为空） */
    public TermEntry(String standardTerm, List<String> aliases, String source) {
        this(standardTerm, aliases, source, null);
    }
}
