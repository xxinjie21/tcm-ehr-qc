package com.tcm.ehr.domain.po;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 术语条目（词典文件与ES文档的统一模型）。
 *
 * <p>不再携带来源与其编码：项目不再声明「术语来源于某国标/某标准」，
 * 词典的参考工具书集中在项目文档里统一声明。</p>
 */
@Data
@NoArgsConstructor
public class TermEntry {

    /** 标准术语 */
    private String standardTerm;

    /** 别名列表（全量入ES，用于模糊归一） */
    private List<String> aliases = new ArrayList<>();

    /** 常规构造（两参：标准词 + 别名） */
    public TermEntry(String standardTerm, List<String> aliases) {
        this.standardTerm = standardTerm;
        this.aliases = aliases == null ? new ArrayList<>() : aliases;
    }
}
