package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;

/**
 * NLP 抽取输入文本拼装。
 *
 * <p>参与抽取的是 12 个叙述/诊断字段，顺序固定、以「。」分隔；与前端的字段分组口径一致
 * （{@code NlpExtract.vue} 的 FIELD_GROUPS）。</p>
 */
public final class NlpTextComposer {

    private NlpTextComposer() {
    }

    /** 由病历 12 字段拼装抽取用文本；无可抽取字段时返回空串 */
    public static String compose(Record r) {
        // 1. 无病历给空串
        if (r == null) {
            return "";
        }
        // 2. 按固定顺序取 12 个字段；顺序即模型看到的叙述顺序，不能随意调换
        StringBuilder sb = new StringBuilder();
        String[] parts = {r.getChiefComplaint(), r.getSelfReport(), r.getPresentIllness(),
                r.getInspection(), r.getTongue(), r.getPulse(), r.getPhysicalExam(),
                r.getTcmDiagnosis(), r.getPattern(), r.getPrescription(),
                r.getFollowUp(), r.getTreatmentEffect()};
        // 3. 逐段追加并补句号：字段边界要显式，否则相邻两段会被模型当成一句
        for (String s : parts) {
            if (s != null && !s.isBlank()) {
                sb.append(s.trim()).append('。');
            }
        }
        return sb.toString();
    }
}
