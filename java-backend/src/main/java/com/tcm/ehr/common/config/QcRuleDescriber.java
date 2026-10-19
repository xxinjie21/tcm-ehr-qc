package com.tcm.ehr.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质控规则 → 自然语言描述。
 *
 * <p>标准面板与规则配置弹窗都消费这里的句子，避免前端各写一套措辞。</p>
 */
public final class QcRuleDescriber {

    private QcRuleDescriber() {
    }

    /**
     * 把一套质控规则摊成一组给业务用户看的句子，顺序为：完整性 → 格式 → 一致性 → 标准化 → 重复 → 分级。
     *
     * <p>这里是规则文案的唯一来源：质控标准面板与规则配置弹窗都消费它，避免前端各写一套措辞。</p>
     *
     * @param r 当前生效规则；为 {@code null} 时返回空列表
     * @return 逐条说明，顺序即界面展示顺序
     */
    public static List<String> describe(QcRuleSet r) {
        List<String> out = new ArrayList<>();
        // 1. 没规则就没说明
        if (r == null) {
            return out;
        }

        // 2. 完整性：列出全部要素名 + 两档扣分
        // 完整性
        List<QcRuleSet.Element> elements = r.getCompleteness().getElements();
        List<String> names = new ArrayList<>();
        for (QcRuleSet.Element e : elements) {
            names.add(e.getName());
        }
        // 扣分档位取自首个要素：出厂默认各要素同权重（见 QcRuleSet.defaults）。
        // 原实现把 full/partial 写在循环里逐次覆盖，只留最后一个要素的值 —— 同权重下输出无差异，
        // 一旦按要素差异化权重就会给出错误文案（审查报告 §4.2）。
        int full = elements.isEmpty() ? 0 : elements.get(0).getWeightFull();
        int partial = elements.isEmpty() ? 0 : elements.get(0).getWeightPartial();
        out.add("病历应包含【" + String.join("、", names) + "】；完全缺失每项扣 " + full
                + " 分，仅有原始记录（未结构化）每项扣 " + partial + " 分。");

        // 格式。regex 类型一律输出 human-readable 的 hint，绝不输出正则本身 ——
        // 这段话是给质控科业务用户看的，正则表达式属于实现细节（审查报告 H3）
        // 3. 格式：每条规则出一句人话
        List<String> fmts = new ArrayList<>();
        for (QcRuleSet.FormatRule f : r.getFormat()) {
            String label = f.getLabel() == null ? f.getField() : f.getLabel();
            String cond;
            // 枚举规则列白名单；regex 优先用 hint，其次 reason
            if ("enum".equalsIgnoreCase(f.getType())) {
                cond = "须为 " + String.join("/", f.getValues());
            } else if (f.getHint() != null && !f.getHint().isBlank()) {
                cond = f.getHint();
            } else {
                cond = f.getReason() == null || f.getReason().isBlank() ? "须符合格式要求" : f.getReason();
            }
            fmts.add("【" + label + "】" + cond + "（- " + f.getWeight() + " 分）");
        }
        if (!fmts.isEmpty()) {
            out.add("格式检查：" + String.join("；", fmts) + "。");
        }

        // 4. 一致性：每条规则一句（触发 → 期望 → 扣分）
        // 一致性
        for (QcRuleSet.ConsistencyRule c : r.getConsistency()) {
            if (c.getTriggerType() == null || c.getExpectType() == null) {
                continue;
            }
            out.add("若【" + labelOf(c.getTriggerType()) + "】含 " + String.join("/", c.getTriggerValues())
                    + "，则【" + labelOf(c.getExpectType()) + "】应为 " + String.join("/", c.getExpectValues())
                    + " 之一；不符且该项有记录时记逻辑冲突（- " + c.getWeight() + " 分）。");
        }

        // 标准化：元素类型存的是英文 key（disease/pattern/…），这里换成中文名再拼 ——
        // 同一条规则在配置弹窗里是中文、在说明里是英文 key，用户会怀疑哪个才算数（审查报告 H3）
        // 5. 术语标准化：类型 key 换中文名
        QcRuleSet.Standardization st = r.getStandardization();
        boolean hasStdTypes = st.getElementTypes() != null && !st.getElementTypes().isEmpty();
        if (st.isEnabled() && hasStdTypes) {
            List<String> typeLabels = new ArrayList<>();
            for (String type : st.getElementTypes()) {
                typeLabels.add(labelOf(type));
            }
            out.add("术语（" + String.join("/", typeLabels)
                    + "）未命中标准词典的：" + st.getWeightEach() + " 分/个，最多先扣 " + st.getCap() + " 个的量；"
                + "再每满 " + st.getCap() + " 个追加一档（避免大量未归一时扣分触顶、分数失去区分度）。"
                + "实际计分取本范围与「术语归一范围」的交集 —— 未开归一的类型不计分。");
        } else if (st.isEnabled()) {
            // 启用了却没选任何类型：说明白，否则用户以为规则没生效
            out.add("术语标准化：已启用，但未勾选任何实体类型（不参与评分）。");
        } else {
            // 关闭也要说明，否则用户以为漏配了规则
            out.add("术语标准化：已关闭（不参与评分）。");
        }

        // 5b. 术语归一范围（与上面的「标准化扣分」是两个独立开关）：哪些类型做词形归一。
        //     未列入的类型不做归一，其实体不写 normLevel，也就不参与上面的未归一计分。
        QcRuleSet.Normalization nz = r.getNormalization();
        boolean hasNormTypes = nz != null && nz.getElementTypes() != null && !nz.getElementTypes().isEmpty();
        if (hasNormTypes) {
            List<String> normLabels = new ArrayList<>();
            for (String type : nz.getElementTypes()) {
                normLabels.add(labelOf(type));
            }
            out.add("术语归一范围：" + String.join("/", normLabels)
                    + " 会做「原文 → 标准词」归一；未列入的类型只保留原文（不参与未归一计分）。");
        } else {
            out.add("术语归一范围：未勾选任何类型（全部不归一）。");
        }

        // 6. 重复与分级：两条固定说明
        // 重复
        out.add("与其它病历内容完全重复的，扣 " + r.getDuplicateWeight() + " 分。");

        // 分级
        QcRuleSet.Thresholds t = r.getThresholds();
        out.add("分级：< " + t.getInvalid() + " 分为无效；存在逻辑冲突为待复核；≥ "
                + t.getReviewUpper() + " 分为合格；其余为待复核。");
        return out;
    }

    /**
     * 一致性规则的构成摘要，如某组织配了规则时输出「3 条（中药 3）」。
     *
     * <p>由规则数据拼装、后端下发。<b>出厂默认无一致性规则（consistency 为空）</b>，
     * 此时输出「0 条」—— 面板如实显示，不再写死条数（原写死「14 条（证候 → 中药 / 舌象 / 脉象）」，
     * 与实际规则不符，审查报告 H2）。</p>
     */
    public static String consistencySummary(QcRuleSet r) {
        // 1. 没规则就说 0 条（出厂默认正是此分支）
        if (r == null || r.getConsistency() == null || r.getConsistency().isEmpty()) {
            return "0 条";
        }
        // 2. 按"期望类型"分组计数（如 中药 3）
        Map<String, Integer> byExpect = new LinkedHashMap<>();
        for (QcRuleSet.ConsistencyRule c : r.getConsistency()) {
            byExpect.merge(labelOf(c.getExpectType()), 1, Integer::sum);
        }
        // 3. 拼成「3 条（中药 3）」
        List<String> parts = new ArrayList<>();
        byExpect.forEach((label, n) -> parts.add(label + " " + n));
        return r.getConsistency().size() + " 条（" + String.join(" / ", parts) + "）";
    }

    /**
     * 可选要素目录：取自实体类型目录（9 类，中文名 ↔ 类型 key / structuredKey / 回退列），用户只选中文。
     *
     * <p>{@code dict=true} 的项即术语标准化可勾选类型（有词典的 8 类，病因除外），
     * 前端据此筛出「术语标准化按哪几类计分」的下拉项。</p>
     */
    public static List<QcRuleSet.Element> catalogElements() {
        // 逐类转成可选项；带上 typeKey，配置弹窗提交时才知道对应哪个类型
        // 1. 遍历 9 类实体，每类产出一个可勾选项（装配走 QcRuleSet.el，与默认规则同构）
        List<QcRuleSet.Element> list = new ArrayList<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            QcRuleSet.Element e = QcRuleSet.el(t.label(), t.structuredKey(), t.fallback());
            e.setTypeKey(t.key());
            e.setDict(t.dict());
            list.add(e);
        }
        return list;
    }

    /** 格式模板目录 */
    public static List<QcRuleSet.FormatRule> catalogFormats() {
        // 两条固定模板：年龄（正则，带 hint）与性别（枚举）。
        // 正则/hint 取 QcRuleSet.AGE_PATTERN / AGE_HINT、性别白名单取 QcRuleSet.GENDER_VALUES
        // （均为唯一权威），不在此处另抄一份 —— 抄了就会与默认规则漂移（审查报告 §2.2）。
        List<QcRuleSet.FormatRule> list = new ArrayList<>();
        list.add(QcRuleSet.format("age", "regex", QcRuleSet.AGE_PATTERN, List.of(), "年龄", "年龄格式不正确",
                QcRuleSet.AGE_HINT));
        list.add(QcRuleSet.format("gender", "enum", null, QcRuleSet.GENDER_VALUES, "性别", "性别非 男/女/未知的性别", null));
        return list;
    }

    private static String labelOf(String type) {
        // 类型未登记就原样返回 key，不抛（文案层不该因脏数据中断）
        EntityTypes.EntityType t = EntityTypes.byKey(type);
        return t == null ? type : t.label();
    }
}
