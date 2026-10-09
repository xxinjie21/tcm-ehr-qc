package com.tcm.ehr.service.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.common.utils.LlmClient;
import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.AiReplyVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IAiService;
import com.tcm.ehr.service.IDictionaryTermStore;
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
 * 绝不阻塞主流程（与 LlmClient 约定一致）。读取受组织数据域约束。</p>
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
            "系统功能：病历导入/查询、NLP 结构化解析、质控评分（完整性/逻辑冲突/格式/术语标准化/重复五类扣分，分级为合格/待复核/无效）、"
            + "人工复核（7 个工作日时限）、数据清洗与术语归一（精确/包含/模糊三级）、标准数据集导出、术语词典管理、日志审计。";
    private static final String KNOWLEDGE_STANDARD =
            "术语标准化依据：系统内置术语词典（疾病/证候/症状/中药/方剂/舌象/脉象/治法各一本参考工具书，"
            + "出处见项目文档统一声明）；归一命中分精确/包含/模糊三级。";

    /** 病历只读表：按统一口径命名为 baseMapper（附录 A.1 #21） */
    private final RecordMapper baseMapper;
    private final IStatsService statsService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ILogService logService;
    private final com.tcm.ehr.common.config.QcRuleStore qcRuleStore;
    /** 词表读取（术语补词建议的召回来源）；与词典页、归一链路同一份 effective 视图 */
    private final IDictionaryTermStore termStore;

    // ------------------------------------------------------------------ 3.1 解读

    /**
     * 解读：规则先出结论（完整性/核心缺项/归一命中/关键提示），LLM 只做叙述增强。
     *
     * <p>LLM 不可用或返回非 JSON 时保留规则模板叙述，结论始终可用。</p>
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
     *
     * @param dto 含 question（必填）与可选 history / recordId
     * @return 回答与来源（rule / llm）
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
        // 按 5 个语义块独立取，再按固定顺序拼接（与原实现的命中顺序逐字一致）；
        // 全部未命中才回落到「功能与流程」，别让模型空答（P3.4 拆分）
        String all = statsBlock(question)
                + standardBlock(question, recordId)
                + currentRecordBlock(question, recordId)
                + knowledgeBlock(question)
                + myLogsBlock(question);
        if (all.isEmpty()) {
            return "【知识】" + KNOWLEDGE_FUNCTION + "\n" + KNOWLEDGE_FLOW + "\n";
        }
        return all;
    }

    /** 块 1：统计类问题 → 看板指标；未命中返回 "" */
    private String statsBlock(String question) {
        if (!containsAny(question, "合格率", "合格", "待复核", "无效", "记录数", "病历数", "总数", "统计", "构成")) {
            return "";
        }
        var ov = statsService.overview();
        return new StringBuilder()
                .append("【看板统计】全库病历 ").append(ov.getTotalRecords()).append(" 条：合格 ")
                .append(ov.getQualifiedCount()).append("（").append(ov.getQualifiedRate()).append("%）、待复核 ")
                .append(ov.getPendingReviewCount()).append("、无效 ").append(ov.getInvalidCount()).append("。\n")
                .toString();
    }

    /** 块 2：归一/标准类问题 → 标准依据 + 当前病历命中分布；未命中返回 "" */
    private String standardBlock(String question, String recordId) {
        if (!containsAny(question, "归一", "命中", "标准化", "标准依据", "术语")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    /** 块 3：指向具体病历的问题 → 当前病历上下文；未命中返回 "" */
    private String currentRecordBlock(String question, String recordId) {
        if (!containsAny(question, "这份病历", "当前病历", "该病历", "这个病历", "本病例", "这条病历")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    /** 块 4：用法/流程类问题 → 功能与流程说明；未命中返回 "" */
    private String knowledgeBlock(String question) {
        if (!containsAny(question, "功能", "怎么用", "如何使用", "流程", "标准依据", "接下来", "下一步")) {
            return "";
        }
        return "【功能】" + KNOWLEDGE_FUNCTION + "\n" + "【流程】" + KNOWLEDGE_FLOW + "\n";
    }

    /** 块 5：问「我做了什么」→ 本人最近操作（脱敏：动作/对象/时间，不含 IP）；未命中返回 "" */
    private String myLogsBlock(String question) {
        if (!containsAny(question, "操作", "日志", "我做了", "做了什么", "审计", "提交了", "操作记录")) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【我的最近操作】");
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
     * null 保持 null，其余按 {@code String.valueOf} 转字符串（<b>不 trim</b>）。
     *
     * <p>与 {@code GovernanceServiceImpl.toTrimmedOrNull}（会 trim、空串归 null）、
     * {@code EsTermIndexServiceImpl.nullToEmpty}（null 变空串）是三种不同语义，
     * 所以名字各不相同 —— 原来三处都叫 {@code str(Object)}，光看调用点无法判断
     * 拿到的是 null 还是空串。</p>
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
     * <p>实测（72 条症状词典 × 质量报告里的 8 个待补词）：门槛取 1 时会把
     * 「食少纳呆 ← 多饮多食多尿」「面红目赤 ← 目盲」这种只共用一个字的候选塞给模型，
     * 而模型<b>倾向于硬凑给定的上下文</b> —— 噪声候选反而会诱导它做出错误归类。
     * 取 2 后只剩「心烦易怒 ← 急躁易怒」「头晕头重 ← 头晕头痛」这类真正有用的。</p>
     */
    private static final int RECALL_MIN_OVERLAP = 2;

    /**
     * 术语补词建议：先按字面召回候选，再让 LLM 在候选约束下判断该怎么补。
     *
     * <p><b>两步的顺序不能颠倒</b>：召回是纯规则能力（不依赖 LLM），先算出来放进兜底结果，
     * 这样 LLM 不可用或输出解析失败时，用户至少还能看到「词表里有哪些字面相近的词」。
     * 与 {@link #interpret} 的「规则先出结论、LLM 只做增强」是同一套降级思路。</p>
     *
     * <p><b>本方法只出候选，不落库。</b>建议要经人工逐条确认才会进词典 ——
     * 归一链路本身不消费本结果，与「判定地基仍是规则引擎」一致。</p>
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
        List<TermEntry> all = termStore.readEffective(RequestUtils.currentOrgId(), type);
        Map<String, List<TermEntry>> recalled = new LinkedHashMap<>();
        for (String t : terms) {
            recalled.put(t, recallCandidates(t, all));
        }
        vo.setTermSuggestions(fallbackSuggestions(terms, recalled));

        // 3. LLM 判断；不可用或输出不可解析，都保留上一步的兜底
        String raw = llmClient.chat(LlmClient.AI_TERM_SUGGEST_SYSTEM_PROMPT,
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
     * 从词表里按<b>字面</b>相似度为原文召回候选。
     *
     * <p>只做字面、不做语义 —— 语义判断交给 LLM。召回的作用是给它一个
     * 「系统已经认这些词」的约束，避免凭空造词。</p>
     */
    private static List<TermEntry> recallCandidates(String content, List<TermEntry> all) {
        List<TermEntry> hit = new ArrayList<>();
        for (TermEntry e : all) {
            if (overlap(content, e) >= RECALL_MIN_OVERLAP) hit.add(e);
        }
        // 按重叠字符数降序；List.sort 是稳定排序，同分保持词表原序
        hit.sort((a, b) -> Integer.compare(overlap(content, b), overlap(content, a)));
        return hit.size() > RECALL_TOP_N ? new ArrayList<>(hit.subList(0, RECALL_TOP_N)) : hit;
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
     * LLM 不可用时的兜底：把召回候选原样列出，action 一律 {@code unknown} 交给人工判断。
     *
     * <p>刻意<b>不猜</b> action —— 猜错会让用户以为系统已经判过了，
     * 反而更容易把口语当成标准词录进去。</p>
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
     * <p>模型不一定听话：可能包 markdown 围栏、可能少给元素。这里只做两件事 ——
     * 剥围栏后按数组解析；解析不出来就返回空集合，由调用方保留召回兜底。
     * <b>不做「字段缺了就补猜」的补救</b>，因为补出来的 action 会误导人工复核。</p>
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
