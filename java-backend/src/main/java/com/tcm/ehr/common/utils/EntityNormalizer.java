package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.NlpExtractVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 实体术语归一（UX-63）：把抽取出的 9 类实体就地归一到国标标准术语。
 *
 * <p><b>与清洗链路的分工</b>：本类服务于<b>解析链路</b>（`POST /api/nlp/extract` 返回前即时归一），
 * 目的是让用户在解析页当场看到「原文 → 标准词」的对照，而不是等到清洗阶段才知道术语是否规范。
 * 清洗链路（{@code GovernanceServiceImpl.normalizeStructuredData}）仍保留<b>兜底补归一</b>——
 * 因为病历可能来自导入、手工新增等未经解析页的入口。</p>
 *
 * <p><b>为什么不会重复归一</b>：两处都只做「原文 → 标准词」的单向替换，标准词本身在词典中
 * 是精确命中项，二次归一得到的仍是同一标准词；清洗链路另有「标准词与原值不同才计数」的判据，
 * 因此已归一实体不会被重复计入统计。</p>
 *
 * <p><b>为什么不下推判定到 ES</b>：与 {@link EsTermNormalizer} 一致——ES 只负责召回候选，
 * 精确 / 包含 / 字符 Dice≥0.8 三级判定仍在 Java 侧完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityNormalizer {

    private final EsTermNormalizer termNormalizer;

    /**
     * 归一统计。
     *
     * @param hit     命中词典的实体数（level 1~3）
     * @param exact   精确命中数
     * @param contain 包含命中数
     * @param fuzzy   模糊命中数
     */
    public record NormStat(int hit, int exact, int contain, int fuzzy) {
        static NormStat of(int[] stat) {
            return new NormStat(stat[1] + stat[2] + stat[3], stat[1], stat[2], stat[3]);
        }
    }

    /**
     * 附录A 字段名 → 词典类型（唯一映射，解析链路与清洗链路共用，避免两处口径漂移）。
     *
     * @return 词典类型；该字段无独立词典时返回 {@code null}
     */
    public static String dictionaryType(String fieldKey) {
        if (fieldKey == null) {
            return null;
        }
        return switch (fieldKey) {
            case "diseases" -> "disease";
            case "symptoms" -> "symptom";
            case "patternList" -> "pattern";
            case "formulaList" -> "formula";
            // tongueList/pulseList/causeList/treatmentList 无独立词典，跳过
            default -> null;
        };
    }

    /** 就地归一抽取结果中的 7 类 Entity 与 herbs，返回命中统计 */
    public NormStat normalize(NlpExtractVO vo) {
        if (vo == null) {
            return new NormStat(0, 0, 0, 0);
        }
        int[] stat = {0, 0, 0, 0};

        // 7 类 Entity 走同一字段→类型映射；无词典的 4 类只回填 sourceText（供前端展示原文）
        normEntities(vo.getDiseases(), "diseases", stat);
        normEntities(vo.getSymptoms(), "symptoms", stat);
        normEntities(vo.getTongueList(), "tongueList", stat);
        normEntities(vo.getPulseList(), "pulseList", stat);
        normEntities(vo.getPatternList(), "patternList", stat);
        normEntities(vo.getCauseList(), "causeList", stat);
        normEntities(vo.getTreatmentList(), "treatmentList", stat);
        normEntities(vo.getFormulaList(), "formulaList", stat);

        for (NlpExtractVO.Herb herb : vo.getHerbs()) {
            if (herb == null) continue;
            // 中药以 name 为归一目标，sourceText 保留原文（模型侧 name 与 sourceText 同源）
            String raw = rawOf(herb.getName(), herb.getSourceText());
            if (raw.isEmpty()) continue;
            if (isBlank(herb.getSourceText())) {
                herb.setSourceText(raw);
            }
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize("herb", raw);
            if (r.source() == null || r.source().isBlank() || r.level() < 1 || r.level() > 3) {
                continue;
            }
            herb.setName(r.standardTerm());
            herb.setNormLevel(r.level());
            herb.setNormSource(r.source());
            herb.setNormCode(r.code());
            stat[0]++;
            stat[r.level()]++;
        }

        return NormStat.of(stat);
    }

    private void normEntities(List<NlpExtractVO.Entity> entities, String fieldKey, int[] stat) {
        if (entities == null) {
            return;
        }
        String type = dictionaryType(fieldKey);
        for (NlpExtractVO.Entity e : entities) {
            if (e == null) continue;
            String raw = rawOf(e.getContent(), e.getSourceText());
            if (raw.isEmpty()) continue;
            if (isBlank(e.getSourceText())) {
                e.setSourceText(raw);
            }
            if (type == null) {
                continue; // 该字段无独立词典，仅保留原文
            }
            EsTermNormalizer.NormalizeResult r = termNormalizer.normalize(type, raw);
            if (r.source() == null || r.source().isBlank() || r.level() < 1 || r.level() > 3) {
                continue;
            }
            e.setContent(r.standardTerm());
            e.setNormLevel(r.level());
            e.setNormSource(r.source());
            e.setNormCode(r.code());
            stat[0]++;
            stat[r.level()]++;
        }
    }

    /** 归一目标值：优先 content，缺失时退回 sourceText（两者在模型侧同源） */
    private String rawOf(String primary, String fallback) {
        if (!isBlank(primary)) return primary.trim();
        if (!isBlank(fallback)) return fallback.trim();
        return "";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
