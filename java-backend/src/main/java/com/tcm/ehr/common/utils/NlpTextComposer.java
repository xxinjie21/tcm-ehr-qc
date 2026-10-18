package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;

import java.util.List;

/**
 * NLP 抽取输入文本拼装。
 *
 * <p>参与抽取的是 12 个叙述/诊断字段，顺序固定、以「。」分隔；与前端的字段分组口径一致
 * （{@code NlpExtract.vue} 的 FIELD_GROUPS）。</p>
 */
public final class NlpTextComposer {

    /**
     * 参与抽取的 12 个 Record 属性名（顺序 = 模型看到的叙述顺序）。
     *
     * <p>抽成常量的理由：{@code compose()} 里的字符串数组与测试、与前端
     * {@code NlpExtract.vue} 的 FIELD_GROUPS 是三处手写清单，已经在评审里出现过一次不一致。
     * 字段名只此一处登记，前端按同名 key 取字段即可。</p>
     */
    public static final List<String> TEXT_FIELDS = List.of(
            "chiefComplaint", "selfReport", "presentIllness", "inspection", "tongue", "pulse",
            "physicalExam", "tcmDiagnosis", "pattern", "prescription", "followUp", "treatmentEffect");

    private NlpTextComposer() {
    }

    /** 由病历 12 字段拼装抽取用文本；无可抽取字段时返回空串 */
    public static String compose(Record r) {
        // 1. 无病历给空串
        if (r == null) {
            return "";
        }
        // 2. 按 TEXT_FIELDS 顺序取值并补句号：字段边界要显式，否则相邻两段会被模型当成一句。
        //    RecordUtil.column 已做 trim + 空白视作无值，取到 null 即跳过。
        StringBuilder sb = new StringBuilder();
        for (String name : TEXT_FIELDS) {
            String s = RecordUtil.column(r, name);
            if (s != null) {
                sb.append(s).append('。');
            }
        }
        return sb.toString();
    }
}
