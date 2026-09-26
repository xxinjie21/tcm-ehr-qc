package com.tcm.ehr.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.LlmClient;
import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.AiReplyVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IAiService;
import com.tcm.ehr.service.ILogService;
import com.tcm.ehr.service.IStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;

/**
 * AI 消费端服务实现。
 *
 * <p>规则优先：解读结论、降级问答由规则产出；LLM 只做叙述增强，异常/不可用一律降级，
 * 绝不阻塞主流程（与 LlmClient 约定一致）。读取受数据域约束（审核员仅待复核域）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements IAiService {

    /** structuredData 的 9 类实体（含 herbs） */
    private static final List<String> LIST_KEYS = List.of(
            "diseases", "symptoms", "tongueList", "pulseList", "patternList",
            "causeList", "treatmentList", "formulaList", "herbs");

    /** 技术实现类问题关键词——命中即兜底拒答（面向使用者，不答实现细节） */
    private static final List<String> TECH_KEYWORDS = List.of(
            "技术实现", "实现细节", "怎么实现", "如何实现", "代码", "源码", "框架", "spring", "vue",
            "数据库", "sql", "表结构", "接口", "api", "部署", "docker", "redis", "nginx", "架构", "算法实现");

    private static final String TECH_REFUSAL = "这属于系统实现细节，建议查看设计文档或咨询开发同学。";

    /** 操作日志时间格式*/
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final String KNOWLEDGE_FLOW =
            "业务主线：原始病历 → 接入 → 结构化解析 → 术语归一 → 质控判定 → 人工复核 → 清洗导出 → 统计评估。";
    private static final String KNOWLEDGE_FUNCTION =
            "系统功能：病历导入/查询、NLP 结构化解析、质控评分（完整性/逻辑/格式三层扣分，分级为合格/待复核/无效）、"
            + "人工复核（7 个工作日时限）、数据清洗与术语归一（精确/包含/模糊三级）、标准数据集导出、术语词典管理、日志审计。";
    private static final String KNOWLEDGE_STANDARD =
            "术语标准化依据：疾病用《中医临床诊疗术语 疾病》、证候用《中医病证分类与代码 GB/T 15657-2021》、"
            + "症状用《中医临床诊疗术语 症状》、中药用《中国药典2025年版》、方剂用《中医方剂大辞典》；"
            + "归一命中分精确/包含/模糊三级。";

    private final RecordMapper recordMapper;
    private final IStatsService statsService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ILogService logService;
    private final com.tcm.ehr.common.config.QcRuleStore qcRuleStore;

    // ------------------------------------------------------------------ 3.1 解读

    /**
     * 解读：规则先出结论（完整性/核心缺项/归一命中/关键提示），LLM 只做叙述增强。
     *
     * <p>LLM 不可用或返回非 JSON 时保留规则模板叙述，结论始终可用。</p>
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
        vo.setKeyHints(keyHints(data, r));

        String template = templateNarrative(vo, r);
        vo.setAnswer(template);

        // 2. 再让 LLM 改写叙述并给要点摘要；不可用或异常都保留上一步的模板叙述
        String raw = llmClient.chat(LlmClient.AI_INTERPRET_SYSTEM_PROMPT, interpretPrompt(vo, r, data));
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
            String json = stripCodeFence(raw);
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            // 2. 有叙述就覆盖模板叙述，没有就沿用模板
            String narrative = str(parsed.get("narrative"));
            if (narrative != null && !narrative.isBlank()) {
                vo.setAnswer(narrative.trim());
            } else {
                vo.setAnswer(fallback);
            }
            // 3. 摘要是可选字段，缺了不影响叙述
            if (parsed.get("summary") instanceof Map<?, ?> sm) {
                AiReplyVO.Summary s = new AiReplyVO.Summary();
                s.setChiefComplaint(str(sm.get("chiefComplaint")));
                s.setDiagnosis(str(sm.get("diagnosis")));
                s.setSyndrome(str(sm.get("syndrome")));
                s.setPrescription(str(sm.get("prescription")));
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
    private String interpretPrompt(AiReplyVO vo, Record r, Map<String, Object> data) {
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
                .append("治法（结构化）：").append(join(contents(data, "treatmentList"))).append('\n')
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
     * <p>也分两档：真缺失 / 漏抽（原始病历有记录但未结构化）。</p>
     */
    private QcScorer.Missing coreMissing(Map<String, Object> data, Record r) {
        return QcScorer.missingElements(data, r, qcRuleStore.get());
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
                if (level == 1) n.setExact(n.getExact() + 1);
                else if (level == 2) n.setContain(n.getContain() + 1);
                else if (level == 3) n.setFuzzy(n.getFuzzy() + 1);
            }
        }
        // 2. 合计 = 精确 + 包含 + 模糊（按归一动作计，不按去重后条数）
        n.setTotal(n.getExact() + n.getContain() + n.getFuzzy());
        return n;
    }

    /** 关键提示：常见空缺 + 核心要素缺失/漏抽 */
    private List<String> keyHints(Map<String, Object> data, Record r) {
        List<String> hints = new ArrayList<>();
        // 1. 先列最常见的空缺：辨证/处方/主诉/中医诊断
        if (blank(r.getPattern())) hints.add("辨证结论为空");
        if (blank(r.getPrescription()) && listEmpty(data, "herbs")) hints.add("处方缺失");
        if (blank(r.getChiefComplaint())) hints.add("主诉为空");
        if (blank(r.getTcmDiagnosis())) hints.add("中医诊断为空");
        // 2. 再把核心要素的两档缺失补进提示（与质控同一口径）
        QcScorer.Missing core = coreMissing(data, r);
        if (!core.full().isEmpty()) {
            hints.add("核心字段真缺失 " + core.full().size() + " 项：" + join(core.full()));
        }
        if (!core.partial().isEmpty()) {
            hints.add("核心字段未抽取 " + core.partial().size() + " 项：" + join(core.partial()));
        }
        return hints;
    }

    // ------------------------------------------------------------------ 3.2 问答

    /**
     * 问答：命中技术实现类关键词直接兜底拒答；否则拼业务上下文交给 LLM，失败降级为规则答案。
     *
     * <p>支持追问：{@code history} 为上文对话，一并进 prompt。</p>
     */
    @Override
    public AiReplyVO chat(AiQueryDTO dto) {
        String question = dto == null ? "" : nz(dto.getQuestion()).trim();
        AiReplyVO vo = new AiReplyVO();

        if (question.isEmpty()) {
            throw new IllegalArgumentException("question不能为空");
        }

        // 1. 命中技术实现类关键词就兜底拒答：这类问题不该由业务助手回答
        String lower = question.toLowerCase();
        if (TECH_KEYWORDS.stream().anyMatch(k -> lower.contains(k.toLowerCase()))) {
            vo.setAnswer(TECH_REFUSAL);
            vo.setSource("rule");
            vo.setLlmAvailable(llmClient.isAvailable());
            return vo;
        }

        // 2. 拼业务上下文 + 上文对话，一起交给模型
        String context = buildContext(question, dto == null ? null : dto.getRecordId());
        String history = dto == null ? null : dto.getHistory();
        String historyBlock = (history == null || history.isBlank())
                ? "" : "\n\n【上文对话】\n" + history.trim();
        String raw = llmClient.chat(LlmClient.AI_CHAT_SYSTEM_PROMPT,
                "【业务上下文】\n" + context + historyBlock + "\n\n【使用者问题】\n" + question);
        boolean llmOk = raw != null && !raw.isBlank();
        vo.setLlmAvailable(llmOk);
        // 3. 模型不可用时退到规则答案（从已拼好的上下文里摘一段）
        if (llmOk) {
            vo.setAnswer(raw.trim());
            vo.setSource("llm");
        } else {
            vo.setAnswer(ruleAnswer(question, context));
            vo.setSource("rule");
        }
        return vo;
    }

    /** 规则检索：按问题关键词注入 stats / 当前病历 / 归一 / 知识；找不到就注入知识+提示 */
    private String buildContext(String question, String recordId) {
        StringBuilder sb = new StringBuilder();
        boolean hit = false;

        // 1. 统计类问题 → 看板指标
        if (containsAny(question, "合格率", "合格", "待复核", "无效", "记录数", "病历数", "总数", "统计", "构成")) {
            var ov = statsService.overview();
            sb.append("【看板统计】全库病历 ").append(ov.getTotalRecords()).append(" 条：合格 ")
                    .append(ov.getQualifiedCount()).append("（").append(ov.getQualifiedRate()).append("%）、待复核 ")
                    .append(ov.getPendingReviewCount()).append("、无效 ").append(ov.getInvalidCount()).append("。\n");
            hit = true;
        }

        // 2. 归一/标准类问题 → 标准依据 + 当前病历命中分布
        if (containsAny(question, "归一", "命中", "标准化", "标准依据", "术语")) {
            sb.append("【归一/标准】").append(KNOWLEDGE_STANDARD).append('\n');
            if (recordId != null && !recordId.isBlank()) {
                Record r = load(recordId);
                if (r != null) {
                    AiReplyVO.NormHits n = normHits(structured(r));
                    sb.append("当前病历归一命中 ").append(n.getTotal()).append(" 处（精确 ")
                            .append(n.getExact()).append(" / 包含 ").append(n.getContain())
                            .append(" / 模糊 ").append(n.getFuzzy()).append("）。\n");
                }
            }
            hit = true;
        }

        // 3. 指向具体病历的问题 → 当前病历上下文（没打开病历时明确说）
        if (containsAny(question, "这份病历", "当前病历", "该病历", "这个病历", "本病例", "这条病历")) {
            Record r = load(recordId);
            if (r == null) {
                sb.append("【当前病历】未在详情中打开病历，无法回答“这份病历”类问题。\n");
            } else {
                sb.append("【当前病历】中医诊断：").append(nz(r.getTcmDiagnosis()))
                        .append("；辨证：").append(nz(r.getPattern()))
                        .append("；评分：").append(r.getScore() == null ? "未评分" : r.getScore())
                        .append("（").append(nz(r.getGrade())).append("）。");
                Map<String, Object> data = structured(r);
                QcScorer.Missing core = coreMissing(data, r);
                sb.append("核心字段缺失：").append(core.full().isEmpty() ? "无" : join(core.full())).append("；");
                sb.append("未抽取到（原始病历有记录）：")
                        .append(core.partial().isEmpty() ? "无" : join(core.partial())).append("。\n");
            }
            hit = true;
        }

        // 4. 用法/流程类问题 → 功能与流程说明
        if (containsAny(question, "功能", "怎么用", "如何使用", "流程", "标准依据", "接下来", "下一步")) {
            sb.append("【功能】").append(KNOWLEDGE_FUNCTION).append('\n');
            sb.append("【流程】").append(KNOWLEDGE_FLOW).append('\n');
            hit = true;
        }

        // 5. 问"我做了什么" → 本人最近操作（脱敏：动作/对象/时间，不含 IP）
        if (containsAny(question, "操作", "日志", "我做了", "做了什么", "审计", "提交了", "操作记录")) {
            sb.append("【我的最近操作】");
            List<OperationLog> recent = logService.listRecentByOperator(RequestUtils.currentUsername(), 50);
            if (recent.isEmpty()) {
                sb.append("没有查到操作记录。\n");
            } else {
                for (OperationLog l : recent) {
                    sb.append(nz(l.getAction()));
                    if (l.getTarget() != null && !l.getTarget().isBlank()) {
                        sb.append('：').append(l.getTarget().trim());
                    }
                    if (l.getLogTime() != null) {
                        sb.append("（").append(l.getLogTime().format(LOG_TS)).append("）");
                    }
                    sb.append('；');
                }
                sb.append('\n');
            }
            hit = true;
        }

        // 6. 一条都没命中 → 至少给功能与流程，别让模型空答
        if (!hit) {
            sb.append("【知识】").append(KNOWLEDGE_FUNCTION).append('\n').append(KNOWLEDGE_FLOW).append('\n');
        }
        return sb.toString();
    }

    /** LLM 不可用时的降级答案：从已拼好的上下文里摘出最相关的一段，没有就回功能与流程说明 */
    private String ruleAnswer(String question, String context) {
        // 1. 优先回"当前病历"，其次"我做了什么"，再次"看板统计"
        StringBuilder sb = new StringBuilder();
        sb.append("（规则问答）");
        if (context.contains("【当前病历】")) {
            int i = context.indexOf("【当前病历】");
            int end = context.indexOf('\n', i);
            sb.append(context, i + "【当前病历】".length(), end < 0 ? context.length() : end);
        } else if (context.contains("【我的最近操作】")) {
            int i = context.indexOf("【我的最近操作】");
            int end = context.indexOf('\n', i);
            sb.append(context, i + "【我的最近操作】".length(), end < 0 ? context.length() : end);
        } else if (context.contains("【看板统计】")) {
            int i = context.indexOf("【看板统计】");
            int end = context.indexOf('\n', i);
            sb.append(context, i + "【看板统计】".length(), end < 0 ? context.length() : end);
        } else {
            sb.append(KNOWLEDGE_FUNCTION).append(' ').append(KNOWLEDGE_FLOW);
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ 复核预检

    /**
     * 复核预检：结论来自规则重算（与 records.qc_results 同源），LLM 只补建议。
     *
     * <p>LLM 不可用时直接回规则预检单，保证"有结论可看"。</p>
     */
    @Override
    public AiReplyVO review(AiQueryDTO dto) {
        Record r = load(dto == null ? null : dto.getRecordId());
        if (r == null) {
            return null;
        }
        Map<String, Object> data = structured(r);

        // 1. 结论来自规则重算（与 records.qc_results 同源，页面看到的判定和这里一致）
        ScoreResultVO sr = QcScorer.score(data, r, false, qcRuleStore.get());
        String precheck = precheckText(sr, r);

        AiReplyVO vo = new AiReplyVO();
        // 2. 扣分项逐条转成提示，用户一眼看到"为什么被扣"
        for (ScoreResultVO.Deduction d : sr.getDeductions()) {
            vo.getKeyHints().add(d.getType() + "：" + d.getReason());
        }

        // 3. LLM 只补建议，失败就直接回规则预检单
        String raw = llmClient.chat(LlmClient.AI_REVIEW_SYSTEM_PROMPT, reviewPrompt(precheck, r, data));
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
                .append("治法：").append(join(contents(data, "treatmentList"))).append('\n')
                .append("方剂：").append(join(contents(data, "formulaList"))).append('\n')
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
        Record r = recordMapper.selectById(recordId);
        if (r == null) {
            return null;
        }
        // 2. 数据域校验：审核员只能看待复核域
        if (RecordFilter.ROLE_AUDITOR.equals(RequestUtils.currentRole()) && !"待复核".equals(r.getGrade())) {
            throw new ForbiddenException("无权查看非待复核病历");
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
            // 2. 坏 JSON 同样给空 map：AI 解读不该被一条脏数据卡住
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
                String c = str(m.get("content") != null ? m.get("content") : m.get("name"));
                if (c != null && !c.isBlank()) out.add(c.trim());
            } else if (item != null) {
                String c = str(item);
                if (c != null && !c.isBlank()) out.add(c.trim());
            }
        }
        return out;
    }

    private static boolean containsAny(String text, String... keys) {
        // 任一关键词被包含即算命中
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private static String join(List<String> list) {
        return list == null || list.isEmpty() ? "无" : String.join("、", list);
    }

    private static int parseInt(Object o) {
        // 1. 数值类型直接取整（JSON 解析出来的整数就是 Number）
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            // 2. 解析不了给 -1：调用侧据此判断"年龄/次数无效"
            return -1;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 去掉模型可能加上的 ``` 围栏，只留 JSON 本体 */
    private String stripCodeFence(String s) {
        String t = s == null ? "" : s.trim();
        // 1. 不是围栏开头就原样返回
        if (t.startsWith("```")) {
            // 2. 去掉首行 ```lang，再去掉末尾 ```
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.trim();
    }
}
