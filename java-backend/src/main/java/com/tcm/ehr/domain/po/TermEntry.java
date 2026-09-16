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
}
