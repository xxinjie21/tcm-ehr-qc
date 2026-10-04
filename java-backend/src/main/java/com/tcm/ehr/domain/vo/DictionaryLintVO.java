package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 词表导入前的质量体检结果（批次 21）。
 *
 *
 * 为什么要有这一步：词表是人工整理的，最常见的几类问题 —— 同名重复、
 *
 * 别名与标准词相同、编码写成错��、术语里混入了整句话 —— 全部
 * 不会报错，只会悄悄进库：重复项在合并时被折叠（看着导入成功，实际少了一条）、
 * 自别名在归一时自己命中自己、错码在对接国标库时才发现对不上。
 * 「不报错」是这类数据缺陷最难发现的地方，所以要在入库之前把它们指出来。
 *
 *
 * 体检只报告不拦截（除硬错误外）：标准的整理方式不该被工具卡住，
 *
 * 是否有疑问由人判断，但必须被告知。
 */
@Data
public class DictionaryLintVO {

    /** 本次导入的词条数 */
    private int total;

    /** 硬错误：必须改，否则入库就是错的 */
    private List<Issue> errors = new ArrayList<>();

    /** 警告：不改也能入库，但大概率是整理时的疏忽 */
    private List<Issue> warnings = new ArrayList<>();

    /** 逐条问题里，责任词条数最多的前 N 个（便于定位） */
    private List<Issue> topIssues = new ArrayList<>();

    /** 是否通过体检（errors 为空即通过） */
    public boolean passed() {
        return errors.isEmpty();
    }

    /** 一条问题 */
    @Data
    public static class Issue {
        /** 问题类型 */
        private String kind;
        /** 人话描述，说明「为什么这是问题」 */
        private String message;
        /** 涉及的标准术语（多个用、分隔） */
        private String terms;
        /** 建议怎么改；为空表示「由人判断」 */
        private String advice;
        /** 严重级别：error / warning */
        private String level;
        /** 影响条数 */
        private int count;
    }
}