package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 组织级词典词条（{@code dictionary_terms}）。
 *
 * <p><b>组织维度</b>：{@code orgId} 为空串 {@code ""} 表示<b>基础层词典</b>（全组织共享）。
 * 空串而非 {@code null} —— {@code null} 在唯一索引里互不相等，会让
 * 「同组织同术语」重复插入拦不住。</p>
 */
@Data
@TableName("dictionary_terms")
public class DictionaryTerm {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属组织；{@code ""} = 基础层（共享） */
    private String orgId;

    /** 术语类型：herb / disease / formula / symptom / material */
    private String type;

    /** 标准术语 */
    private String standardTerm;

    /** 国标代码，可空 */
    private String code;

    /** 来源标准，可空 */
    private String source;

    /** 别名列表（JSON 数组字符串） */
    private String aliasesJson;
}
