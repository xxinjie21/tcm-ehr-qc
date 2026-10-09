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

    /**
     * 别名列表（JSON 数组文本，如 {@code ["国老","国老草"]}）。
     *
     * <p><b>列名是 {@code aliases}，不是 {@code aliases_json}</b> —— 批次 4 建的表
     * 用的就是 {@code aliases json}。写成 {@code aliasesJson} 会让 MyBatis-Plus
     * 映射到不存在的 {@code aliases_json} 列，报
     * {@code Unknown column 'aliases_json' in 'field list'}，
     * 而服务仍能启动（启动器按类型逐个 catch），只是词典加载不了、归一全落空。
     * 改列名前务必用脚本逐列比对实体与 {@code SHOW COLUMNS}，别照着印象写。</p>
     */
    private String aliases;
}
