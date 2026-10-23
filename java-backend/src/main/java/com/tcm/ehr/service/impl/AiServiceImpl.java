package com.tcm.ehr.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.config.AiFeature;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.common.utils.LlmClient;
import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.AiReplyVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IAiService;
import com.tcm.ehr.service.IDictionaryTermStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 消费端服务实现。
 *
 * 规则优先：解读结论由规则产出；LLM 只做叙述增强，异常/不可用一律降级，
 * 绝不阻塞主流程（与 LlmClient 约定一致）。读取受组织数据域约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements IAiService {

    /** structuredData 的 9 类实体（含 herbs） */
    private static final List<String> LIST_KEYS = List.of(
            "diseases", "symptoms", "tongueList", "pulseList", "patternList",
            "causeList", "treatmentList", "formulaList", "herbs");

    /** 病历只读表：按统一口径命名为 baseMapper（附录 A.1 #21） */
    private final RecordMapper baseMapper;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final com.tcm.ehr.common.config.QcRuleStore qcRuleStore;
    /** 词表读取（术语补词建议的召回来源）；与词典页、归一链路同一份 effective 视图 */
    private final IDictionaryTermStore termStore;

    // ------------------------------------------------------------------ 3.1 解读

    /**
     * 解读：规则先出结论（完整性/核心缺项/归一命中/关键提示），LLM 只做叙述增强。
     *
     * LLM 不可用或返回非 JSON 时保留规则模板叙述，结论始终可用。
     *
     * @param dto 含 recordId 的解读请求；病历不属于当前组时返回 {@code null}
     * @return 解读结果，由 Controller 转 止 404
     */
    @Override
    public AiReplyVO interpret(AiQueryDTO dto) {
        Record r = load(dto == null ? null : dto.getRecordId());
        if (r == null) {
            return null;
        }
        Map<String, Object> data = structured(r);

        AiReplyVO vo = new AiReplyVO();

        // 1. 先用规则把结论定下来：完整性 / 核心缺项 / 归一命中 / 关键提示
        //    这一步不依赖 LLM，模型挂了它就是最终答案
        vo.setCompleteness(completeness(r));
        QcScorer.Missing core = coreMissing(data, r);
        vo.setCoreMissing(core.full());
        vo.setCorePartial(core.partial());
        vo.setNormHits(normHits(data));
        // 核心缺失只算一次，直接传给 keyHints —— 它要用的正是这份结果（P2-13）
        vo.setKeyHints(keyHints(data, r, core));

        String template = templateNarrative(vo, r);
        vo.setAnswer(template);

        // 2. 再让 LLM 改写叙述并给要点摘要；不可用或异常都保留上一步的模板叙述
        String raw = llmClient.chat(AiFeature.INTERPRET, LlmClient.AI_INTERPRET_SYSTEM_PROMPT, interpretPrompt(vo, r));
        boolean llmOk = raw != null && !raw.isBlank();
        vo.setLlmAvailable(llmOk);
        vo.setSource(llmOk ? "llm" : "rule");
        // 3. 模型可能不按 JSON 返回，解析失败时按纯文本叙述处理，结论仍然有效
        if (llmOk) {
            applyLlmInterpret(vo, raw, template);
        } else {
            vo.setSummary(null);
        }
        return vo;
    }

    /** 把 LLM 返回的 JSON 叙述覆盖到模板叙述上；模型没按 JSON 返回则原文即叙述 */
    private void applyLlmInterpret(AiReplyVO vo, String raw, String fallback) {
        try {
            // 1. 模型回的是 JSON 文本，可能带 ``` 围栏，先剥掉
            String json = TextUtil.stripCodeFence(raw);
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            // 2. 有叙述就覆盖模板叙述，没有就沿用模板
            String narrative = rawOrNull(parsed.get("narrative"));
            if (narrative != null && !narrative.isBlank()) {
                vo.setAnswer(narrative.trim());
            } else {
                vo.setAnswer(fallback);
            }
            // 3. 摘要是可选字段，缺了不影响叙述
            if (parsed.get("summary") instanceof Map<?, ?> sm) {
                AiReplyVO.Summary s = new AiReplyVO.Summary();
                s.setChiefComplaint(rawOrNull(sm.get("chiefComplaint")));
                s.setDiagnosis(rawOrNull(sm.get("diagnosis")));
                s.setSyndrome(rawOrNull(sm.get("syndrome")));
                s.setPrescription(rawOrNull(sm.get("prescription")));
                vo.setSummary(s);
            }
        } catch (JacksonException e) {
            // 模型没按 JSON 返回：原文即叙述，不出摘要
            log.debug("[AI] 解读返回非 JSON，按纯文本叙述处理");
            vo.setAnswer(raw.trim());
            vo.setSummary(null);
        }
    }

    /** 解读 prompt：把规则结论 + 病历关键字段拼给模型，要求只回 JSON */
    private String interpretPrompt(AiReplyVO vo, Record r) {
        // 1. 先给规则结论，模型只做叙述加工，避免它自己下结论
        StringBuilder sb = new StringBuilder("【规则预检结论】\n");
        sb.append("- 完整性：21 字段完整 ").append(vo.getCompleteness().getPresent())
                .append(" 项，缺失：").append(join(vo.getCompleteness().getMissing())).append('\n');
        sb.append("- 核心字段缺失：").append(join(vo.getCoreMissing())).append('\n');
        sb.append("- 核心字段未抽取（原始病历有记录，可能未被抽取）：")
                .append(join(vo.getCorePartial())).append('\n');
        AiReplyVO.NormHits n = vo.getNormHits();
        sb.append("- 归一命中：共 ").append(n.getTotal()).append(" 处（精确 ")
                .append(n.getExact()).append(" / 包含 ").append(n.getContain())
                .append(" / 模糊 ").append(n.getFuzzy()).append("）\n");
        sb.append("- 关键提示：").append(join(vo.getKeyHints())).append('\n');
        sb.append("\n【病历关键字段】\n")                .append("主诉：").append(nz(r.getChiefComplaint())).append('\n')
                .append("中医诊断：").append(nz(r.getTcmDiagnosis())).append('\n')
                .append("辨证结论：").append(nz(r.getPattern())).append('\n')
                .append("草药：").append(nz(r.getPrescription())).append('\n');
        sb.append("\n请只输出要求的 JSON 对象。");
        return sb.toString();
    }

    /** 规则模板叙述（LLM 不可用时的降级） */
    private String templateNarrative(AiReplyVO vo, Record r) {
        AiReplyVO.Completeness c = vo.getCompleteness();
        AiReplyVO.NormHits n = vo.getNormHits();
        StringBuilder sb = new StringBuilder();
        // 1. 先说 21 字段完整度与缺失项
        sb.append("规则预检：21 字段中完整 ").append(c.getPresent()).append(" 项");
        if (!c.getMissing().isEmpty()) {
            sb.append("，缺失 ").append(c.getMissing().size()).append(" 项（")
                    .append(join(c.getMissing())).append("）");
        }
        sb.append("。");
        // 2. 核心要素两档都要说：只说真缺失会得出「核心字段齐全」，
        //    而质控侧此时可能正在扣「漏抽」的分
        if (vo.getCoreMissing().isEmpty() && vo.getCorePartial().isEmpty()) {
            sb.append("核心字段齐全。");
        } else {
            if (!vo.getCoreMissing().isEmpty()) {
                sb.append("核心字段缺失：").append(join(vo.getCoreMissing())).append("。");
            }
            if (!vo.getCorePartial().isEmpty()) {
                sb.append("核心字段未抽取到（原始病历有记录）：").append(join(vo.getCorePartial())).append("。");
            }
        }
        // 3. 再给归一命中分布与关键提示
        sb.append("术语归一命中 ").append(n.getTotal()).append(" 处（精确 ").append(n.getExact())
                .append(" / 包含 ").append(n.getContain()).append(" / 模糊 ").append(n.getFuzzy()).append("）。");
        if (!vo.getKeyHints().isEmpty()) {
            sb.append("关键提示：").append(join(vo.getKeyHints())).append("。");
        }
        sb.append("（AI辅助分析，最终以人工复核为准）");
        return sb.toString();
    }

    /** 21 个原始字段的完整度：逐字段判空并列出缺失项 */
    private AiReplyVO.Completeness completeness(Record r) {
        // 1. 按固定顺序摆 21 个字段，保证前端展示与统计口径一致
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("登记号", r.getRegistrationNo());
        fields.put("门诊号", r.getOutpatientNo());
        fields.put("性别", r.getGender());
        fields.put("年龄", r.getAge());
        fields.put("就诊次数", r.getVisitCount() == null ? null : String.valueOf(r.getVisitCount()));
        fields.put("西医诊断", r.getWesternDiagnosis());
        fields.put("中医诊断", r.getTcmDiagnosis());
        fields.put("主诉", r.getChiefComplaint());
        fields.put("自诉", r.getSelfReport());
        fields.put("现病史", r.getPresentIllness());
        fields.put("望诊", r.getInspection());
        fields.put("脉诊", r.getPulse());
        fields.put("舌诊", r.getTongue());
        fields.put("查体", r.getPhysicalExam());
        fields.put("辨证结论", r.getPattern());
        fields.put("草药", r.getPrescription());
        fields.put("随访", r.getFollowUp());
        fields.put("治疗效果", r.getTreatmentEffect());
        fields.put("开单科室", r.getDepartment());
        fields.put("医生工号", r.getDoctorId());
        fields.put("接诊时间", r.getVisitTime() == null ? null : r.getVisitTime().toString());

        // 2. 逐字段判空：非空计 present，空的记进缺失清单
        AiReplyVO.Completeness c = new AiReplyVO.Completeness();
        c.setTotal(fields.size());
        int present = 0;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                present++;
            } else {
                c.getMissing().add(e.getKey());
            }
        }
        c.setPresent(present);
        return c;
    }

    /**
     * 核心要素缺失，直接问评分器，两侧共用同一份要素清单与判空口径。
     *
     * 也分两档：真缺失 / 漏抽（原始病历有记录但未结构化）。
     */
    private QcScorer.Missing coreMissing(Map<String, Object> data, Record r) {
        // 与质控页同口径：按当前组织取规则，不能用进程内基线 get()
        return QcScorer.missingElements(data, r, qcRuleStore.getFor(RequestUtils.currentOrgId()));
    }

    /** 统计 9 类实体的归一命中数，按精确/包含/模糊分档 */
    private AiReplyVO.NormHits normHits(Map<String, Object> data) {
        AiReplyVO.NormHits n = new AiReplyVO.NormHits();
        // 1. 只数带 normLevel 的实体（未归一/未命中词典的不计）
        for (String key : LIST_KEYS) {
            if (!(data.get(key) instanceof List<?> list)) continue;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Object lv = m.get("normLevel");
                if (lv == null) continue;
                int level = parseInt(lv);
                // 用具名常量而非裸字面量：层级语义由 EsTermNormalizer 定义，
                // 判定顺序若调整，这里跟着改常量即可，写死数字会静默错位
                if (level == EsTermNormalizer.LEVEL_EXACT) n.setExact(n.getExact() + 1);
                else if (level == EsTermNormalizer.LEVEL_CONTAIN) n.setContain(n.getContain() + 1);
                else if (level == EsTermNormalizer.LEVEL_FUZZY) n.setFuzzy(n.getFuzzy() + 1);
            }
        }
        // 2. 合计 = 精确 + 包含 + 模糊（按归一动作计，不按去重后条数）
        n.setTotal(n.getExact() + n.getContain() + n.getFuzzy());
        return n;
    }

    /**
     * 关键提示：常见空缺 + 核心要素缺失/漏抽。
     *
     * core 由调用方传入 —— 它与 vo.coreMissing 是同一份结果，
     * 本方法不再自己算一遍（P2-13）。
     */
    private List<String> keyHints(Map<String, Object> data, Record r, QcScorer.Missing core) {
        List<String> hints = new ArrayList<>();
        // 1. 先列最常见的空缺：辨证/处方/主诉/中医诊断
        if (blank(r.getPattern())) hints.add("辨证结论为空");
        if (blank(r.getPrescription()) && listEmpty(data, "herbs")) hints.add("处方缺失");
        if (blank(r.getChiefComplaint())) hints.add("主诉为空");
        if (blank(r.getTcmDiagnosis())) hints.add("中医诊断为空");
        // 2. 再把核心要素的两档缺失补进提示（与质控同一口径）
        if (!core.full().isEmpty()) {
            hints.add("核心字段真缺失 " + core.full().size() + " 项：" + join(core.full()));
        }
        if (!core.partial().isEmpty()) {
            hints.add("核心字段未抽取 " + core.partial().size() + " 项：" + join(core.partial()));
        }
        return hints;
    }

    // ------------------------------------------------------------------ 复核预检

    /**
     * 复核预检：结论来自规则重算（与 records.qc_results 同源），LLM 只补建议。
     *
     * LLM 不可用时直接回规则预检单，保证"有结论可看"。
     *
     * @param dto 含 recordId 的预检请求；无权时返回 {@code null}
     * @return 预检单，由 Controller 转 止 404
     */
    @Override
    public AiReplyVO review(AiQueryDTO dto) {
        Record r = load(dto == null ? null : dto.getRecordId());
        if (r == null) {
            return null;
        }
        Map<String, Object> data = structured(r);

        // 1. 结论来自规则重算（与 records.qc_results 同源，页面看到的判定和这里一致）
        //    规则同样按当前组织取，否则与质控页的判定分叉
        ScoreResultVO sr = QcScorer.score(data, r, false,
                qcRuleStore.getFor(RequestUtils.currentOrgId()));
        String precheck = precheckText(sr, r);

        AiReplyVO vo = new AiReplyVO();
        // 2. 扣分项逐条转成提示，用户一眼看到"为什么被扣"
        for (ScoreResultVO.Deduction d : sr.getDeductions()) {
            vo.getKeyHints().add(d.getType() + "：" + d.getReason());
        }

        // 3. LLM 只补建议，失败就直接回规则预检单
        String raw = llmClient.chat(AiFeature.REVIEW, LlmClient.AI_REVIEW_SYSTEM_PROMPT, reviewPrompt(precheck, r, data));
        boolean llmOk = raw != null && !raw.isBlank();
        vo.setLlmAvailable(llmOk);
        vo.setSource(llmOk ? "llm" : "rule");
        vo.setAnswer(llmOk ? raw.trim() : precheck);
        return vo;
    }

    /** 规则预检单文本（评分/分级 + 扣分项 + 逻辑冲突 + 当前辨证） */
    private String precheckText(ScoreResultVO sr, Record r) {
        StringBuilder sb = new StringBuilder();
        // 1. 评分与分级
        sb.append("规则预检单：评分 ").append(sr.getScore()).append("，分级 ").append(sr.getGrade()).append("。");
        // 2. 逐条扣分项，让模型知道"为什么扣"
        if (sr.getDeductions().isEmpty()) {
            sb.append("无扣分项。");
        } else {
            sb.append("扣分项：");
            sb.append(String.join("；", sr.getDeductions().stream()
                    .map(d -> d.getType() + "-" + d.getItem() + "（-" + d.getPoints() + "，" + d.getReason() + "）")
                    .toList()));
            sb.append("。");
        }
        // 3. 逻辑冲突与当前辨证：让模型对着实际内容给建议
        if (!sr.getLogicConflicts().isEmpty()) {
            sb.append("逻辑冲突：").append(String.join("；", sr.getLogicConflicts())).append("。");
        }
        sb.append("当前辨证：").append(blank(r.getPattern()) ? "（空）" : r.getPattern());
        return sb.toString();
    }

    /** 复核 prompt：预检单 + 结构化要素，要求给出复核建议 */
    private String reviewPrompt(String precheck, Record r, Map<String, Object> data) {
        // 1. 先给规则预检单（判定地基），再给结构化要素供模型对照
        StringBuilder sb = new StringBuilder("【规则预检单】\n").append(precheck).append('\n');
        sb.append("【结构化数据】\n")
                .append("证候：").append(join(contents(data, "patternList"))).append('\n')
                .append("舌象：").append(join(contents(data, "tongueList"))).append('\n')
                .append("脉象：").append(join(contents(data, "pulseList"))).append('\n');
        sb.append("\n请给出复核建议。");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 工具

    /** 载入病历并做数据域校验；不存在返回 null（控制器回 404） */
    private Record load(String recordId) {
        // 1. ID 为空或查不到都返回 null，由控制器统一回 404
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        Record r = baseMapper.selectById(recordId);
        if (r == null) {
            return null;
        }
        // § 6.3 缺点 1：对任意组病历做 AI 解读 = 越权读取品质与隐私。
        // 不属于本组统一止 404（不控接 ForbiddenException说明存在）。
        if (!RecordFilter.canAccess(r)) {
            return null;
        }
        return r;
    }

    /** 解析结构化数据；为空或坏 JSON 时返回空 map，不抛（AI 侧降级为"无结构化"） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> structured(Record r) {
        // 1. 没结构化数据给空 map（不是 null：调用侧直接 .get 就行）
        if (r.getStructuredData() == null || r.getStructuredData().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(r.getStructuredData(), new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            // 2. 坏 JSON 同样给空 map：AI 解读不该被一条脏数据卡住。
            //    但必须留痕：否则「这条病历没有结构化数据」与「JSON 坏了」
            //    在解读结果里完全无法区分，脏数据会一直无人发现
            log.warn("[AI] 病历 {} 的 structured_data 解析失败，按空数据解读：{}",
                    r.getId(), e.getOriginalMessage());
            return Map.of();
        }
    }

    private boolean listEmpty(Map<String, Object> data, String key) {
        return !(data.get(key) instanceof List<?> list) || list.isEmpty();
    }

    /** 取某类实体的文本：herbs 取 name，其余取 content */
    private List<String> contents(Map<String, Object> data, String key) {
        List<String> out = new ArrayList<>();
        // 1. 该 key 不是列表就当没有
        if (!(data.get(key) instanceof List<?> list)) return out;
        // 2. 逐项取文本：Map 取 content（缺则 name），非 Map 直接转字符串
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                String c = rawOrNull(m.get("content") != null ? m.get("content") : m.get("name"));
                if (c != null && !c.isBlank()) out.add(c.trim());
            } else if (item != null) {
                String c = rawOrNull(item);
                if (c != null && !c.isBlank()) out.add(c.trim());
            }
        }
        return out;
    }

    private static String join(List<String> list) {
        return list == null || list.isEmpty() ? "无" : String.join("、", list);
    }

    private static int parseInt(Object value) {
        // 1. 数值类型直接取整（JSON 解析出来的整数就是 Number）
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            // 2. 解析不了给 -1：调用侧据此判断"年龄/次数无效"
            return -1;
        }
    }

    /**
     * null 保持 null，其余按 String.valueOf 转字符串（不 trim）。
     *
     * 与 GovernanceServiceImpl.toTrimmedOrNull（会 trim、空串归 null）、
     * EsTermIndexServiceImpl.nullToEmpty（null 变空串）是三种不同语义，
     * 所以名字各不相同 —— 原来三处都叫 {@code str(Object)}，光看调用点无法判断
     * 拿到的是 null 还是空串。
     */
    private static String rawOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------ 3.4 术语补词建议

    /** 召回候选条数上限：够模型判断即可，给太多反而稀释注意力 */
    private static final int RECALL_TOP_N = 5;

    /**
     * 召回门槛：与原文重叠的字符数低于此值即视为噪声，不放进候选。
     *
     * 实测（72 条症状词典 × 质量报告里的 8 个待补词）：门槛取 1 时会把
     * 「食少纳呆 ← 多饮多食多尿」「面红目赤 ← 目盲」这种只共用一个字的候选塞给模型，
     * 而模型倾向于硬凑给定的上下文 —— 噪声候选反而会诱导它做出错误归类。
     * 取 2 后只剩「心烦易怒 ← 急躁易怒」「头晕头重 ← 头晕头痛」这类真正有用的。
     */
    private static final int RECALL_MIN_OVERLAP = 2;

    /**
     * 术语补词建议：先按字面召回候选，再让 LLM 在候选约束下判断该怎么补。
     *
     * 两步的顺序不能颠倒：召回是纯规则能力（不依赖 LLM），先算出来放进兜底结果，
     * 这样 LLM 不可用或输出解析失败时，用户至少还能看到「词表里有哪些字面相近的词」。
     * 与 {@link #interpret} 的「规则先出结论、LLM 只做增强」是同一套降级思路。
     *
     * 本方法只出候选，不落库。建议要经人工逐条确认才会进词典 ——
     * 归一链路本身不消费本结果，与「判定地基仍是规则引擎」一致。
     */
    @Override
    public AiReplyVO suggestTerms(AiQueryDTO dto) {
        // 1. 归一入参：去空、去重；类型缺省按 symptom（质量报告的待补词恒来自症状类）
        List<String> terms = new ArrayList<>();
        if (dto != null && dto.getTerms() != null) {
            for (String t : dto.getTerms()) {
                if (t == null) continue;
                String v = t.trim();
                if (!v.isEmpty() && !terms.contains(v)) terms.add(v);
            }
        }
        String type = dto == null || blank(dto.getTermType()) ? "symptom" : dto.getTermType().trim();

        AiReplyVO vo = new AiReplyVO();

        // 2. 规则地基：从现有词表按字面相似度召回候选（这一步不依赖 LLM）
        //    P1-15：词条与它的字符倒排表一次取回（同源，不会出现「索引与词条对不上」）
        IDictionaryTermStore.RecallIndex index = termStore.recallIndex(RequestUtils.currentOrgId(), type);
        // 暂存区按「一次建议请求」复用：待补词有 T 个，若每个都新分配一份标记数组，
        // 国标全量（D≈3 万）下就是 T×30KB 的瞬时垃圾
        boolean[] marked = new boolean[index.entries().size()];
        List<Integer> candidates = new ArrayList<>();
        Map<String, List<TermEntry>> recalled = new LinkedHashMap<>();
        for (String t : terms) {
            recalled.put(t, recallCandidates(t, index, marked, candidates));
        }
        vo.setTermSuggestions(fallbackSuggestions(terms, recalled));

        // 3. LLM 判断；不可用或输出不可解析，都保留上一步的兜底
        String raw = llmClient.chat(AiFeature.TERM_SUGGEST, LlmClient.AI_TERM_SUGGEST_SYSTEM_PROMPT,
                termSuggestPrompt(terms, recalled, type));
        boolean llmOk = raw != null && !raw.isBlank();
        vo.setLlmAvailable(llmOk);
        vo.setSource(llmOk ? "llm" : "rule");
        if (llmOk) {
            List<AiReplyVO.TermSuggestion> parsed = parseSuggestions(raw, terms);
            if (!parsed.isEmpty()) {
                vo.setTermSuggestions(parsed);
            }
        }

        // 4. answer 只做一句概述，真正的结果在 termSuggestions 里
        int n = vo.getTermSuggestions() == null ? 0 : vo.getTermSuggestions().size();
        vo.setAnswer(llmOk
                ? "已生成 " + n + " 条补词建议，请逐条确认后录入；AI 建议仅供参考，以人工判断为准。"
                : "AI 暂不可用，已列出词表中字面相近的候选，请人工判断后录入。");
        return vo;
    }

    /**
     * 从词表里按字面相似度为原文召回候选。
     *
     * 只做字面、不做语义 —— 语义判断交给 LLM。召回的作用是给它一个
     * 「系统已经认这些词」的约束，避免凭空造词。
     *
     * P1-15 / S1：先用倒排表把候选缩出来，再在候选内按原口径打分。
     * 原实现对每个待补词全量扫一遍词表（O(T×D×L²)）：证候类 2080 条时单条约两三千次字符比对，
     * 国标全量导入后（D≈3 万）单条约 4.5 万次、T=500 就是千万级。
     *
     * 为什么结果集不变：门槛是「与原文重叠 ≥{@value #RECALL_MIN_OVERLAP} 个字符」，
     * 而重叠只可能发生在「出现在原文里的字符」上 ⇒「出现在原文任一字符的倒排表里」是入选的
     * 必要条件。按它取候选是超集，落选的那些本来重叠就是 0，过不了门槛。
     * 过滤阶段零精度损失；打分函数（{@link #overlap}）一字未动。
     *
     * 包级可见是为了让「与全表扫描逐条一致」这条性质能被同步测试直接钉住 ——
     * 它正是这次改造唯一可能出错的地方。
     */
    static List<TermEntry> recallCandidates(String content, IDictionaryTermStore.RecallIndex index) {
        return recallCandidates(content, index, new boolean[index.entries().size()], new ArrayList<>());
    }

    /**
     * 召回主实现：marked / candidates 由调用方提供并复用（见 {@link #suggestTerms}）。
     *
     * 复用它们是因为「一次建议请求要跑 T 个待补词」：国标全量（D≈3 万）下，
     * 每个词各分配一份标记数组就是 T×30KB 的瞬时垃圾，而每个词各扫一遍全表又是 T×D 次判断。
     * 包级可见同样是为了可测：复用的暂存区若漏复位，会静默把上一个词条的候选带进下一个。
     */
    static List<TermEntry> recallCandidates(String content, IDictionaryTermStore.RecallIndex index,
                                            boolean[] marked, List<Integer> candidates) {
        List<TermEntry> all = index.entries();
        // 1. 倒排表取候选：出现在原文任一字符的倒排表里的词条（超集，见方法注释）
        //    只收「候选下标」而不是回扫全表 —— 候选数 C 通常比词表 D 小两个数量级
        int[] stringOwner = index.stringOwner();
        for (int i = 0; i < content.length(); i++) {
            int[] posting = index.postings().get(content.charAt(i));
            if (posting == null) {
                continue;
            }
            for (int sid : posting) {
                int t = stringOwner[sid];
                if (!marked[t]) {
                    marked[t] = true;
                    candidates.add(t);
                }
            }
        }
        // 2. 候选按下标升序 —— 必须与「全表顺序」一致：后面按重叠数排序是**稳定排序**，
        //    同分时保持的就是这里的先后，顺序错了会让同分候选的取舍与改造前不同
        candidates.sort(null);
        // 3. 候选内按原口径打分，并把分数**预计算**下来
        //    （AI-7/A3：原先在排序比较器里现算 2 次 overlap，同一次召回里同一个词条被算好几遍）
        List<int[]> hit = new ArrayList<>();
        for (int t : candidates) {
            int ov = overlap(content, all.get(t));
            if (ov >= RECALL_MIN_OVERLAP) {
                hit.add(new int[]{t, ov});
            }
        }
        // 4. 复位标记（只清本词标记过的那几个，不整表清零），供下一个待补词复用同一份数组
        for (int t : candidates) {
            marked[t] = false;
        }
        candidates.clear();
        // 5. 按重叠字符数降序；List.sort 是稳定排序，同分保持词表原序
        hit.sort((a, b) -> Integer.compare(b[1], a[1]));
        int n = Math.min(hit.size(), RECALL_TOP_N);
        List<TermEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(all.get(hit.get(i)[0]));
        }
        return out;
    }

    /** 原文与某词条的最大字符重叠数（标准词与各别名取最大） */
    private static int overlap(String content, TermEntry e) {
        int best = commonChars(content, e.getStandardTerm());
        if (e.getAliases() != null) {
            for (String a : e.getAliases()) {
                best = Math.max(best, commonChars(content, a));
            }
        }
        return best;
    }

    /** 两串的字符交集大小（按字符去重）。中文短词用这个足够，不必引入编辑距离的复杂度 */
    private static int commonChars(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        StringBuilder rest = new StringBuilder(b);
        int n = 0;
        for (char c : a.toCharArray()) {
            int i = rest.indexOf(String.valueOf(c));
            if (i >= 0) {
                n++;
                rest.deleteCharAt(i);
            }
        }
        return n;
    }

    /**
     * LLM 不可用时的兜底：把召回候选原样列出，action 一律 unknown 交给人工判断。
     *
     * 刻意不猜 action —— 猜错会让用户以为系统已经判过了，
     * 反而更容易把口语当成标准词录进去。
     */
    private static List<AiReplyVO.TermSuggestion> fallbackSuggestions(List<String> terms,
                                                                     Map<String, List<TermEntry>> recalled) {
        List<AiReplyVO.TermSuggestion> out = new ArrayList<>();
        for (String t : terms) {
            AiReplyVO.TermSuggestion s = new AiReplyVO.TermSuggestion();
            s.setOriginal(t);
            s.setAction("unknown");
            s.setSource("dict");
            List<TermEntry> cs = recalled.getOrDefault(t, List.of());
            if (cs.isEmpty()) {
                s.setReason("词表里没有字面相近的词，需人工判断是规范术语还是口语");
            } else {
                List<String> names = new ArrayList<>();
                for (TermEntry e : cs) names.add(e.getStandardTerm());
                s.setReason("词表里的相近词：" + String.join("、", names));
            }
            out.add(s);
        }
        return out;
    }

    /** 组装用户提示词：原文与各自召回到的候选成对列出，模型只需在候选里做判断 */
    private static String termSuggestPrompt(List<String> terms, Map<String, List<TermEntry>> recalled,
                                           String type) {
        StringBuilder sb = new StringBuilder();
        sb.append("术语类型：").append(type).append("\n\n待规范原文与候选词：\n");
        for (String t : terms) {
            sb.append("- 原文：").append(t).append("\n  候选：");
            List<TermEntry> cs = recalled.getOrDefault(t, List.of());
            if (cs.isEmpty()) {
                sb.append("（词表里没有字面相近的词）");
            } else {
                List<String> parts = new ArrayList<>();
                for (TermEntry e : cs) {
                    String one = e.getStandardTerm();
                    if (e.getAliases() != null && !e.getAliases().isEmpty()) {
                        one += "（别名：" + String.join("、", e.getAliases()) + "）";
                    }
                    parts.add(one);
                }
                sb.append(String.join("；", parts));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 解析模型输出的 JSON 数组。
     *
     * 模型不一定听话：可能包 markdown 围栏、可能少给元素。这里只做两件事 ——
     * 剥围栏后按数组解析；解析不出来就返回空集合，由调用方保留召回兜底。
     * 不做「字段缺了就补猜」的补救，因为补出来的 action 会误导人工复核。
     */
    private List<AiReplyVO.TermSuggestion> parseSuggestions(String raw, List<String> terms) {
        try {
            String json = TextUtil.stripCodeFence(raw);
            List<Map<String, Object>> list = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<AiReplyVO.TermSuggestion> out = new ArrayList<>();
            for (Map<String, Object> m : list) {
                if (m == null) continue;
                AiReplyVO.TermSuggestion s = new AiReplyVO.TermSuggestion();
                s.setOriginal(nz(rawOrNull(m.get("original"))).trim());
                if (s.getOriginal().isEmpty()) continue;
                String action = nz(rawOrNull(m.get("action"))).trim();
                s.setAction(action.isEmpty() ? "unknown" : action);
                s.setStandardTerm(nz(rawOrNull(m.get("standardTerm"))).trim());
                String src = nz(rawOrNull(m.get("source"))).trim();
                s.setSource(src.isEmpty() ? "model" : src);
                s.setReason(nz(rawOrNull(m.get("reason"))).trim());
                if (m.get("aliases") instanceof List<?> al) {
                    for (Object o : al) {
                        String v = nz(rawOrNull(o)).trim();
                        if (!v.isEmpty() && !v.equals(s.getStandardTerm()) && !s.getAliases().contains(v)) {
                            s.getAliases().add(v);
                        }
                    }
                }
                out.add(s);
            }
            // 数量对不上（模型漏项）不整体丢弃，但记一条日志便于排查
            if (out.size() != terms.size()) {
                log.warn("[AI 补词建议] 模型返回 {} 条，输入 {} 条，按返回结果采用", out.size(), terms.size());
            }
            return out;
        } catch (Exception e) {
            log.warn("[AI 补词建议] 模型输出无法解析为 JSON，降级为召回候选：{}", e.getMessage());
            return List.of();
        }
    }

}
