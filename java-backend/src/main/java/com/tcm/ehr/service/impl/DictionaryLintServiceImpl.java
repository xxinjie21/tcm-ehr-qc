package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.DictionaryLintVO;
import com.tcm.ehr.service.IDictionaryLintService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 词表质量体检实现（批次 21）。
 *
 *
 * 检查项按「会不会悄悄出错」排序，不按「多不多」排序。真正咬人的是前三条：
 *
 *
 * <ol>
 *   - 同名重复：合并时同名会被折叠，看着导入成功、实际少一条。
 *       更麻烦的是播种路径会直接撞唯一键 uk_org_type_term 整批失败
 *       （2026-10-04 舌象词典就是这么没灌进去的，现象只是「报告里舌象 0 条」）。
 *   - 别名与标准词相同：归一时该词会自己命中自己，
 *       掩盖真正的匹配逻辑，也让「词表命中数」虚高。
 *   - 编码格式可疑：国标码有固定形态，写错了不会报错，
 *       直到对接国标库 / 医保库时才发现对不上。
 * </ol>
 *
 *
 * 其余几项是「大概率是整理时的疏忽」，只警告不拦。
 *
 */
@Slf4j
@Service
public class DictionaryLintServiceImpl implements IDictionaryLintService {

    /** 国标/ICD 编码的常见形态：字母数字组合，可含 . - */
    private static final Pattern CODE_SHAPE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.\\-]{2,29}$");
    /** 术语里出现句号/逗号，多半是把定义或一整句塞进了标准词 */
    private static final Pattern LOOKS_LIKE_SENTENCE = Pattern.compile("[。；;，,、]");
    /**
     * 「流水短语」的信号：不含标点，但用了这些只出现在描述性短语里的连接成分。
     *
     *
     * 为什么要单独识别：早期那份症状词表里混着「流清涕反复发作」「双下肢水肿」，
     *
     * 它们没有任何标点，光看标点查不出来，但同样是把病历原文抄进来当标准词 ——
     * 后果是归一时拿原文匹配自己，看着命中了、其实没做任何标准化。
     *
     *
     * <b>标记词只在词尾才算信号</b>：单个标记词做子串匹配会误伤合法术语 ——
     * 「发作」会把「发作期」判成原文短语（实测误报，提示词与被标记的词对不上）。
     * 所以只有「反复发作 / 反复出现」这种整串出现，或标记词恰好落在词尾时才算命中。
     */
    private static final Pattern PHRASE_MARKERS =
            Pattern.compile("反复发作|反复出现|(反复|发作|加重|减轻|明显|持续|多年|数次)$");
    /** 单字标准词：药名「姜」「术」确实存在，但出现得多半是抽取截断被粘进来了 */
    private static final int MIN_TERM_LEN = 2;
    private static final int MAX_TERM_LEN = 20;
    /** 报告里各类型各取几条高频问题 */
    private static final int TOP_N = 12;

    @Override
    public DictionaryLintVO lint(String type, List<TermEntry> entries) {
        DictionaryLintVO vo = new DictionaryLintVO();
        if (entries == null || entries.isEmpty()) {
            vo.getErrors().add(issue("empty", "词表里没有任何词条", "", "确认导入的文件不是空表，且第一行是表头或 JSON 数组", "error"));
            vo.setTotal(0);
            return vo;
        }
        vo.setTotal(entries.size());

        lintDuplicateTerm(entries, vo);
        lintSelfAlias(entries, vo);
        lintCode(entries, vo);
        lintTermShape(entries, vo);
        lintAliasSanity(entries, vo);

        // 高频问题置顶：同一种毛病往往成片出现，先改一处能消掉一大片
        List<DictionaryLintVO.Issue> all = new ArrayList<>();
        all.addAll(vo.getErrors());
        all.addAll(vo.getWarnings());
        all.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        vo.setTopIssues(new ArrayList<>(all.subList(0, Math.min(TOP_N, all.size()))));
        return vo;
    }

    @Override
    public DictionaryLintVO lintSafely(String type, List<TermEntry> entries) {
        try {
            return lint(type, entries);
        } catch (Exception e) {
            log.warn("[词典] 词表体检失败（按空结论返回）: {}", e.getMessage(), e);
            DictionaryLintVO empty = new DictionaryLintVO();
            empty.setTotal(entries == null ? 0 : entries.size());
            return empty;
        }
    }

    // ------------------------------------------------------------ 检查项

    /**
     * 同名重复：硬错误。
     *
     *
     * 为什么算硬错误：合并逻辑按 standardTerm 建 LinkedHashMap，同名后一条会覆盖
     *
     * 前一条的 code/source（别名取并集），导入报「成功 N 条」但实际少了词 ——
     * 账面与实存不符，之后没人会发现。
     */
    private void lintDuplicateTerm(List<TermEntry> entries, DictionaryLintVO vo) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        Map<String, List<String>> details = new LinkedHashMap<>();
        for (TermEntry e : entries) {
            String term = trimmed(e.getStandardTerm());
            if (term.isEmpty()) {
                continue;
            }
            seen.merge(term, 1, Integer::sum);
            List<String> d = details.computeIfAbsent(term, k -> new ArrayList<>());
            d.add(describe(e));
        }
        List<String> dups = new ArrayList<>();
        for (Map.Entry<String, Integer> en : seen.entrySet()) {
            if (en.getValue() > 1) {
                dups.add(en.getKey() + "（" + en.getValue() + " 条：" + String.join(" / ", details.get(en.getKey())) + "）");
            }
        }
        if (!dups.isEmpty()) {
            vo.getErrors().add(issue("duplicate-term",
                    "同一个标准术语出现了多次",
                    String.join("；", dups.subList(0, Math.min(dups.size(), 8))),
                    "同一标准术语只能留一条。把重复项的别名并进那一条，或确认确实不同后再拆开。"
                            + "注意：重复项在导入时会被折叠，看着导入成功、实际词条会少；"
                            + "走播种路径时更会直接整批失败。",
                    "error", dups.size()));
        }
    }

    /**
     * 别名与标准词相同：警告。
     *
     *
     * 归一时该词会自己命中自己（normLevel=1），掩盖真实匹配，也让词表命中数虚高。
     *
     * 现有导入的 normalize() 会自动丢掉这类别名，所以这主要是提醒「源文件里写了」，
     * 而不是「库里一定有」。
     */
    private void lintSelfAlias(List<TermEntry> entries, DictionaryLintVO vo) {
        List<String> bad = new ArrayList<>();
        for (TermEntry e : entries) {
            String term = trimmed(e.getStandardTerm());
            if (term.isEmpty() || e.getAliases() == null) {
                continue;
            }
            if (e.getAliases().stream().anyMatch(a -> term.equals(trimmed(a)))) {
                bad.add(term);
            }
        }
        if (!bad.isEmpty()) {
            vo.getWarnings().add(issue("self-alias",
                    "别名里写了标准词自己",
                    String.join("、", bad.subList(0, Math.min(bad.size(), 10))),
                    "标准词不必再写进别名（归一时本来就能精确命中）。"
                            + "导入会自动清理，但源文件留着会让后面核对的人以为还有别的别名。",
                    "warning", bad.size()));
        }
    }

    /**
     * 编码：形态可疑或重复时警告。
     *
     *
     * 不按具体标准体系校验（国标码与 ICD 码形态本来就不同，且各词典类型可能用不同体系），
     *
     * 只校验「像不像一个编码」与「有没有重复」——
     * 重复编码比格式错更危险，它会让两个术语抢同一个码。
     */
    private void lintCode(List<TermEntry> entries, DictionaryLintVO vo) {
        Map<String, List<String>> codeToTerms = new LinkedHashMap<>();
        List<String> badShape = new ArrayList<>();
        for (TermEntry e : entries) {
            String term = trimmed(e.getStandardTerm());
            String code = trimmed(e.getCode());
            if (code.isEmpty()) {
                continue;
            }
            if (!CODE_SHAPE.matcher(code).matches()) {
                badShape.add(term + "→" + code);
            }
            codeToTerms.computeIfAbsent(code, k -> new ArrayList<>()).add(term);
        }
        if (!badShape.isEmpty()) {
            vo.getWarnings().add(issue("code-shape",
                    "编码格式看起来不像编码",
                    String.join("、", badShape.subList(0, Math.min(badShape.size(), 8))),
                    "编码一般是字母数字与「.」「-」的组合（3~30 位）。"
                            + "如果这里填的是别的东西（如来源说明），应该放到来源列而不是编码列。",
                    "warning", badShape.size()));
        }
        List<String> dupCode = new ArrayList<>();
        for (Map.Entry<String, List<String>> en : codeToTerms.entrySet()) {
            if (en.getValue().size() > 1) {
                dupCode.add(en.getKey() + "（" + String.join(" / ", en.getValue()) + "）");
            }
        }
        if (!dupCode.isEmpty()) {
            vo.getWarnings().add(issue("code-duplicate",
                    "多个术语用了同一个编码",
                    String.join("；", dupCode.subList(0, Math.min(dupCode.size(), 8))),
                    "编码必须一对一。重复会导致按编码反查时张冠李戴，对接国标库后尤其明显。",
                    "warning", dupCode.size()));
        }
    }

    /**
     * 术语形态：含句读或过长过短时警告。
     *
     *
     * 真实事故：早期那份症状词表里混进了「流清涕反复发作」「双下肢水肿」这类
     *
     * 从病历原文整段摘抄的短语，还有「腰部冷痛 → 别名 腰部冷疼痛」这种机械拼接 ——
     * 都是把「抽取结果」当成「标准词表」了。这类词条能进来，但会让归一命中看起来正常，
     * 实际是拿原文匹配自己。
     */
    private void lintTermShape(List<TermEntry> entries, DictionaryLintVO vo) {
        List<String> sentenceLike = new ArrayList<>();
        List<String> tooShort = new ArrayList<>();
        List<String> tooLong = new ArrayList<>();
        for (TermEntry e : entries) {
            String term = trimmed(e.getStandardTerm());
            if (term.isEmpty()) {
                continue;
            }
            if (LOOKS_LIKE_SENTENCE.matcher(term).find()
                    || PHRASE_MARKERS.matcher(term).find()) {
                sentenceLike.add(term);
            } else if (term.length() < MIN_TERM_LEN) {
                tooShort.add(term);
            } else if (term.length() > MAX_TERM_LEN) {
                tooLong.add(term);
            }
        }
        addIfAny(vo, sentenceLike, "term-looks-like-sentence",
                "标准术语是整句描述，不是规范名词",
                "例：把「流清涕反复发作」直接当标准词。应改成标准词「流清涕」、"
                        + "把「流清涕反复发作」放进别名 —— "
                        + "否则归一时是拿原文匹配自己，看着命中了、其实没做标准化。");
        addIfAny(vo, tooShort, "term-too-short",
                "标准术语只有一个字",
                "药名确有单字（「姜」），但占比高时多半是抽取截断的残字被粘进来了，请核对来源。");
        addIfAny(vo, tooLong, "term-too-long",
                "标准术语过长",
                "超过 20 字的术语多半是一整句描述或复合了多个症状，建议拆成标准词 + 别名。");
    }

    /** 别名里出现明显重复（同一别名在多条里重复出现），提示可能的批量录入错误 */
    private void lintAliasSanity(List<TermEntry> entries, DictionaryLintVO vo) {
        Map<String, List<String>> aliasOwners = new LinkedHashMap<>();
        for (TermEntry e : entries) {
            if (e.getAliases() == null) {
                continue;
            }
            for (String a : e.getAliases()) {
                String alias = trimmed(a);
                if (alias.isEmpty()) {
                    continue;
                }
                aliasOwners.computeIfAbsent(alias, k -> new ArrayList<>()).add(trimmed(e.getStandardTerm()));
            }
        }
        // 同一别名挂在多个标准词下是正常的（一个症状名可属多个证），只提示数量异常多的
        List<String> shared = new ArrayList<>();
        for (Map.Entry<String, List<String>> en : aliasOwners.entrySet()) {
            Set<String> owners = new LinkedHashSet<>(en.getValue());
            if (owners.size() >= 3) {
                shared.add(en.getKey() + "（" + String.join("/", owners) + "）");
            }
        }
        if (shared.size() > 5) {
            vo.getWarnings().add(issue("alias-shared",
                    "不少别名同时挂在 3 个以上术语下",
                    String.join("；", shared.subList(0, Math.min(shared.size(), 8)))
                            + "（共 " + shared.size() + " 个）",
                    "同义词被多个术语共用本身可以接受，但数量偏多时要确认不是"
                            + "把一整类症状都挂到了同一个泛称下面 —— 那会让归一失去区分度。",
                    "warning", shared.size()));
        }
    }

    // ------------------------------------------------------------ 工具

    private void addIfAny(DictionaryLintVO vo, List<String> terms, String kind,
                          String message, String advice) {
        if (terms.isEmpty()) {
            return;
        }
        vo.getWarnings().add(issue(kind, message,
                String.join("、", terms.subList(0, Math.min(terms.size(), 10))),
                advice, "warning", terms.size()));
    }

    private DictionaryLintVO.Issue issue(String kind, String message, String terms,
                                        String advice, String level) {
        return issue(kind, message, terms, advice, level, 1);
    }

    private DictionaryLintVO.Issue issue(String kind, String message, String terms,
                                        String advice, String level, int count) {
        DictionaryLintVO.Issue i = new DictionaryLintVO.Issue();
        i.setKind(kind);
        i.setMessage(message);
        i.setTerms(terms);
        i.setAdvice(advice);
        i.setLevel(level);
        i.setCount(count);
        return i;
    }

    private String describe(TermEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("别名").append(e.getAliases() == null ? "[]" : e.getAliases());
        String code = trimmed(e.getCode());
        if (!code.isEmpty()) {
            sb.append("/码").append(code);
        }
        return sb.toString();
    }

    private String trimmed(String s) {
        return s == null ? "" : s.trim();
    }
}