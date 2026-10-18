package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.config.EntityTypes;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RegexCache;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.StandardizationReportVO;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.IStandardizationReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标准化质量报告实现（批次 24）。
 *
 *
 * 只读：只 select，不写库、不重建索引、不触发质控重算。
 *
 *
 *
 * 为什么报告要分甲乙两层：甲类只取决于词表建得对不对（与数据无关），
 *
 * 乙类取决于这批数据长什么样。500 条是 10 个模板生成的合成数据，乙类只能当下限用；
 * 混在一张表里，读者会把「模板数据的归一率」当成「真实病历的准确率」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardizationReportServiceImpl implements IStandardizationReportService {

    /**
     * 分桶阈值：长度 ≤ 2 的未归一实体。
     *
     * <p><b>它只回答「这条实体短不短」，不回答「是不是抽取截断的残字」。</b>
     * 原注释写的是「残词（归一不可能命中标准词）」，这个前提已被实测推翻：
     * 症状词典只有 110 条标准词，而高频未归一 2 字实体恰恰是「鼻塞」「咽痛」「咽痒」
     * 这类**完整**的症状词 —— 它们是词表没收录，不是归一命不中。分项归属见
     * {@code unmatched(...)} 的 fragment 分支。</p>
     */
    private static final int FRAGMENT_MAX_LEN = 2;
    /**
     * 分类错放：脉象/舌象要素不该出现在症状里。
     *
     * <p>判据除「脉」「舌」前缀外，还覆盖<b>书写时省略了前缀</b>的两类 ——
     * 脉诊三部（尺/寸/关 打头：尺沉、寸浮、关缓）与舌诊专名（尖/根/苔 打头：
     * 尖红=舌尖红、根苔=舌根苔、苔平）。三部与舌诊专名都是脉诊/舌诊的固定用语，
     * 症状域不会出现，故可无歧义判定，且**不限字数**（实测 3~7 字的描述性短语
     * 反而居多：根部厚腻 129、尺后偏沉 62）。2026-10-15 实测这批无前缀的共
     * 3057 条 / 2384 条记录。只判前缀时它们会掉进 {@link #unmatched} 的
     * fragment 桶与 dictionaryGap 桶，被误当成「词表缺口的二字症状词」——
     * 而那两桶其实是舌脉要素，补症状词是补错了方向。</p>
     *
     * <p>与 Python 侧 {@code python-nlp/nlp_rules.py#_is_pulse_tongue} 及
     * {@code tools/reroute-pulse-tongue-stored.py} 三处同口径：同一件事不能各写一套。
     * 那里额外排除了「关节」「尺肤」「苔藓」「尖锐」四个首字撞上的症状域真词，
     * 以及含非汉字的抽取噪声 —— 本处同样排除，见 {@link #MISROUTED_EXCLUDE}。</p>
     */
    private static final String[] MISROUTED_PREFIX = {
            "脉", "舌",                 // 带前缀
            "尺", "寸", "关",           // 脉诊三部（省略「脉」）
            "尖", "根", "苔"            // 舌诊专名（省略「舌」）
    };
    /**
     * 首字撞上脉诊三部 / 舌诊专名，但<b>确实是症状</b>的前缀，一律不当错放。
     *
     * <p>四个都是实测踩出来的坑（首字撞上，语义却属症状域）：</p>
     * <ul>
     *   <li><b>关节</b> —— 关节痛 / 关节怕冷 / 关节屈伸不利 …… 实测症状里有 90+
     *       个不同写法（关节痛 一条 164 次），是量最大的一类；</li>
     *   <li><b>尺肤</b> —— 尺部皮肤，诊断体征（尺肤热 / 尺肤凉 / 尺肤粘），
     *       与脉象是两回事；</li>
     *   <li><b>苔藓</b> —— 皮肤病（苔藓 / 苔藓样皮损），不是舌苔；</li>
     *   <li><b>尖锐</b> —— 声音 / 湿疣等义（尖锐声），脉象里没有「尖锐」。</li>
     * </ul>
     * <p>误判会把真症状判成「不可归一」：症状归一率虚高、错放数虚高，
     * 两边都是假信号。口径与 Python 侧 {@code _PT_BLACKLIST} 完全一致。</p>
     */
    private static final String[] MISROUTED_EXCLUDE = {"关节", "尺肤", "苔藓", "尖锐"};

    /**
     * 该未归一症状实体是否是「脉象/舌象要素错放进了症状」。
     *
     * <p>与抽取侧 {@code _is_pulse_tongue} 同口径（都不限字数），
     * 只多一层保险：抽取侧靠第二字是否汉字挡噪声，统计侧靠
     * {@link #MISROUTED_EXCLUDE} 挡首字撞车的症状域真词。</p>
     */
    private boolean isMisrouted(String content) {
        if (startsWithAny(content, MISROUTED_EXCLUDE)
                || !startsWithAny(content, MISROUTED_PREFIX)) {
            return false;
        }
        // 含非汉字的抽取噪声（实测「关尺>寸」这类夹了比较符号的）不当舌脉，
        // 与抽取侧 _is_pulse_tongue 的汉字门同口径 —— 否则报告会把它算成错放，
        // 而抽取侧又不会去修，两边数字永远对不上。
        return content.chars().allMatch(ch -> ch >= 0x4e00 && ch <= 0x9fff);
    }
    /** 体征错放的关键字：属体征而非症状 */
    private static final String[] PHYSICAL_SIGN = {"压痛", "触痛", "叩痛", "反跳痛"};
    /** 有意保留在页面上的声明，防止报告被当成科研级准确率引用 */
    private static final String DISCLAIMER =
            "本报告基于现有数据的实测统计。乙类（数据集覆盖度）只用于验证词表是否建全，"
            + "不代表真实病历上的准确率；甲类（标准符合度）才是词表质量的目标口径。";

    private final com.tcm.ehr.mapper.RecordMapper recordMapper;
    private final IDictionaryTermStore termStore;
    /** 与 StatsServiceImpl 同一个注入实例，不另建 —— 每次 new ObjectMapper 会有可观的初始化开销 */
    private final tools.jackson.databind.ObjectMapper objectMapper;
    /** 报告缓存（性能审查：报告是全站最重的读接口，进页即全量拉数据域 + 每条解析 2 次 JSON） */
    private final com.tcm.ehr.common.cache.StatsCache statsCache;

    /** 报告缓存基准 TTL：比看板长（变更更稀、计算更重），仍带 ±10s 抖动 */
    private static final java.time.Duration REPORT_TTL = java.time.Duration.ofSeconds(180);

    @Override
    public StandardizationReportVO report() {
        return report(new FiltersDTO());
    }

    @Override
    public StandardizationReportVO report(String start, String end) {
        return report(dateRangeFilters(start, end));
    }

    /**
     * 28.23：报告 CSV 由后端生成，与另外两条导出（数据集 / 日志）统一走文件流。
     *
     * <p>列固定为 区块 / 指标 / 数值 / 说明；中文靠 UTF-8 BOM 让 Excel 正确识别。
     * 行内容与报告页展示一一对应，前端不再自行拼 CSV。</p>
     */
    @Override
    public byte[] reportCsv(String start, String end) {
        return reportCsv(dateRangeFilters(start, end));
    }

    @Override
    public byte[] reportCsv(FiltersDTO filters) {
        StandardizationReportVO r = report(filters);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("区块", "指标", "数值", "说明"));
        for (StandardizationReportVO.DictQuality d : r.getDictQuality()) {
            String label = nz(d.getLabel());
            rows.add(List.of("词典质量", label + " 词条数", String.valueOf(d.getTermCount()), "共 " + d.getTermCount() + " 条"));
            rows.add(List.of("词典质量", label + " 有别名", String.valueOf(d.getAliasedCount()), "共 " + d.getTermCount() + " 条"));
            rows.add(List.of("词典质量", label + " 别名重复", String.valueOf(d.getSelfAliasCount()), "别名含标准词本身会自命中"));
        }
        rows.add(List.of("词典质量", "同名术语跨词典",
                String.valueOf(r.getCrossTypeDuplicates().size()), "同一词出现在多本词典"));
        for (StandardizationReportVO.TypeCoverage c : r.getCoverage()) {
            rows.add(List.of("归一情况", nz(c.getLabel()) + " 归一率",
                    c.getTotal() > 0 ? pct(c.getNormalized(), c.getTotal()) : "未抽取",
                    "抽取 " + c.getTotal() + " 条"));
        }
        StandardizationReportVO.UnmatchedBreakdown u = r.getUnmatched();
        rows.add(List.of("未归一构成", "词表未收录", String.valueOf(u.getDictionaryGap()), "责任：词表"));
        rows.add(List.of("未归一构成", "脉/舌被当成症状", String.valueOf(u.getMisrouted()), "责任：抽取"));
        rows.add(List.of("未归一构成", "体征被当成症状", String.valueOf(u.getPhysicalSign()), "责任：抽取"));
        rows.add(List.of("未归一构成", "抽取残词", String.valueOf(u.getFragment()), "责任：抽取"));
        rows.add(List.of("未归一构成", "合计", String.valueOf(u.getTotal()), ""));
        StandardizationReportVO.ScoreDistribution s = r.getScore();
        rows.add(List.of("评分", "扣分相同占比", s.getTotal() > 0 ? pct(s.getCapped(), s.getTotal()) : "—",
                "平均分 " + s.getAvg() + "，区间 " + s.getMin() + "~" + s.getMax()));
        StandardizationReportVO.TimeRange rg = r.getRange();
        rows.add(List.of("区间", "统计区间",
                (rg.getStart() == null ? "不限" : rg.getStart()) + " ~ "
                        + (rg.getEnd() == null ? "不限" : rg.getEnd()),
                "区间内 " + rg.getRecords() + " 条，排除 " + rg.getExcluded() + " 条"));
        // 导入区间（2026-10-17 起报告默认按导入时间收窄）：CSV 必须体现这个维度，
        // 否则导出文件看不出这批数字是「今天导入的」还是全库的
        if (rg.getImportStart() != null && rg.getImportEnd() != null) {
            rows.add(List.of("区间", "导入区间", rg.getImportStart() + " ~ " + rg.getImportEnd(),
                    "按导入时间（records.create_time）收窄"));
        }
        for (StandardizationReportVO.MonthlyBucket m : r.getByMonth()) {
            rows.add(List.of("按月份", m.getMonth() + " 病历数", String.valueOf(m.getRecords()), ""));
            rows.add(List.of("按月份", m.getMonth() + " 症状归一率",
                    m.getSymptomRate() == null ? "未抽取" : m.getSymptomRate(), ""));
            rows.add(List.of("按月份", m.getMonth() + " 词表缺口", String.valueOf(m.getDictionaryGap()), "补词表可解决"));
            rows.add(List.of("按月份", m.getMonth() + " 平均分", String.valueOf(m.getAvgScore()), "扣分封顶 " + m.getCapped()));
        }
        StandardizationReportVO.DatasetShape ds = r.getDataset();
        rows.add(List.of("数据集", "病历总数", String.valueOf(ds.getRecordCount()), ""));
        rows.add(List.of("数据集", "主诉写法种类", String.valueOf(ds.getChiefComplaintTemplates()), "远小于病历数说明是测试数据"));
        rows.add(List.of("数据集", "来自患者口语", String.valueOf(ds.getRecordsWithColloquialSymptom()), "口语不是标准症状词"));
        rows.add(List.of("声明", nz(r.getDisclaimer()), "", nz(r.getGeneratedAt())));
        return csvBytes(rows);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 生成带 UTF-8 BOM 的 CSV：单元格一律加引号并转义内部引号，行尾 CRLF */
    private static byte[] csvBytes(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(nz(row.get(i)).replace("\"", "\"\"")).append('"');
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 按范围出报告（科室 / 就诊时间 / 导入时间）。
     *
     * <p>甲类（词典质量 / 跨类重名）看的是词表本身，与病历无关，**不随范围收窄**；
     * 只有乙类（病历相关统计）按范围过滤。</p>
     *
     * <p>两个时间维度的过滤位置不同、各有理由：</p>
     * <ul>
     *   <li><b>接诊时间</b>（dateRange）留在 Java 侧（{@link #filterByVisitTime}），
     *       因为 {@code range.excluded}（「已排除 N 条区间外数据」）要的是
     *       「范围内但因时间被排除的条数」，只有「先取范围全集、再按时间筛」两步才能算出来；</li>
     *   <li><b>导入时间</b>（importRange）下推到 SQL（{@code records.create_time}），
     *       与病历列表 / Qc 页同一口径；它的排除量不进 excluded（那本是接诊时间的语义），
     *       回显走 {@code range.importStart/importEnd}。</li>
     * </ul>
     *
     * <p>不随范围收窄的还有<b>证候</b>：该页是组织级口径、无证候维度展示，且证候是
     * LIKE 子串模糊匹配，子集统计的参考性弱 —— 2026-10-17 定案不再暴露（接口与前端
     * 同步移除，FiltersDTO 的 pattern 字段仍服务于病历列表 / 统计看板）。</p>
     */
    @Override
    public StandardizationReportVO report(FiltersDTO filters) {
        FiltersDTO f = filters == null ? new FiltersDTO() : filters;
        // ---- 缓存包装（报告页是全站最重的读接口：全量拉数据域 + 每条解析 2 次 JSON）----
        // ① 入参（department / dateRange / importRange / grade）全在白名单内 —— getOrLoadSwr 会再核一遍，
        //    白名单外（奇怪字符 / 超长）自动绕过缓存现算，保证 key 空间有界；
        // ② 时间范围 / 科室不变但换词典后数字会变 → 词典版本作 salt 拼进 key，换词表即换 key；
        // ③ 失效用 StatsCache 的全局版本段：任一写入（导入 / 清洗 / 复核 / 批次）立即作废，
        //    不依赖「被改数据所属 org」，管理员写入落别组也不会漏失效；
        // ④ SWR：时间到期后先秒回旧值、后台重算 —— 那 ~5s 冷算（其中 ~3s 是读 110MB JSON 的
        //    磁盘 I/O，SQL 侧已无优化空间）不再挡在用户前面；而写入走版本号 ⇒ key 变 ⇒
        //    两把 key 都落空 ⇒ 仍阻塞现算新值（正确性优先，绝不端出写前的旧数字）。
        //
        // ⚠️ 后台重算的线程没有请求上下文：必须在**请求线程**先把 orgId / 数据域 / 看全部标记
        //    捕获成快照，否则后台线程里 RequestUtils 读到 "unknown"、RecordFilter 全 fail-closed，
        //    会算出错误（甚至空）的报告并回填缓存。见 ReportContext。
        ReportContext ctx = new ReportContext(
                RequestUtils.currentOrgId(), RecordFilter.domainOrgId(), RequestUtils.viewAllOrgs());
        String dictVersion = termStore.effectiveDictVersion(ctx.orgId());
        return statsCache.getOrLoadSwr("report", f, dictVersion, REPORT_TTL,
                () -> computeReport(f, ctx), StandardizationReportVO.class);
    }

    /**
     * 报告现算所需的请求上下文快照（在<b>请求线程</b>捕获）。
     *
     * <p>SWR 的陈旧分支会在后台线程重算，那里读不到 {@code RequestContextHolder}
     * （{@code RequestUtils} 一律返回 {@code "unknown"}、{@code RecordFilter} fail-closed）。
     * 把三个取值定死在请求线程，现算体就与线程无关。做法与本项目批任务的
     * {@code qc_task.role} / {@code org_id} 快照一致。</p>
     *
     * @param orgId       当前组织（读词表用）
     * @param domainOrgId 数据域落在 {@code org_id} 列上的取值（无组 → 不可能值哨兵）
     * @param viewAllOrgs 管理员「看全部」标记（true 时不加组织条件）
     */
    private record ReportContext(String orgId, String domainOrgId, boolean viewAllOrgs) {
    }

    /** 报告现算主体（不含缓存）。缓存包装见 {@link #report(FiltersDTO)}。 */
    private StandardizationReportVO computeReport(FiltersDTO f, ReportContext ctx) {
        // 时间上下界必须同时给才生效，与 report(String, String) 及 StatsController 同一口径
        String start = rangeBound(f.getDateRange(), true);
        String end = rangeBound(f.getDateRange(), false);
        // 导入时间（records.create_time）与接诊时间是两个独立维度，判据同样两端齐全；
        // 区别在过滤位置：接诊时间留在 Java 侧（要算 excluded），导入时间下推到 SQL
        // （RecordFilter 的 create_time 条件，与病历列表 / Qc 页同一口径）。
        String importStart = rangeBound(f.getImportRange(), true);
        String importEnd = rangeBound(f.getImportRange(), false);

        StandardizationReportVO vo = new StandardizationReportVO();
        // 生效词表一次读回（性能审查 P1-1：原 dictQuality / crossTypeDuplicates / coverage /
        // symptomTerms 各自逐类 readEffective，8 类 × 2 层 ≈ 32 条 SQL；现整趟 1 条）。
        Map<String, List<TermEntry>> dicts = termStore.readEffectiveAll(ctx.orgId());
        vo.setDictQuality(dictQuality(dicts));
        vo.setCrossTypeDuplicates(crossTypeDuplicates(dicts));
        // 乙类：病历相关，必须先按数据域收窄，否则登录即可的接口会读到跨组织数据。
        // A1/T2 + C1：这里**一次遍历、一次解析**，抽扁平中间结构（Row）向下传给各统计器。
        // 原实现里同一条 structured_data 被 7 处统计器各自解析一遍（4 万条 ≈ 28 万次 Jackson 解析），
        // 且 selectList 无列投影（21 TEXT + 2 JSON 全量进堆，4 万条约 45MB）。
        // 科室下沉到 SQL（RecordFilter），时间留在 Java 侧（见方法注释）。
        List<Row> all = rowsInDomain(ctx, withoutVisitRange(f));
        List<Row> rows = filterByVisitTime(all, start, end);
        vo.setRange(rangeOf(all, rows, start, end, importStart, importEnd));
        vo.setByMonth(byMonth(rows));
        vo.setCoverage(coverage(rows, dicts));
        // A2：词表只读一次，两个统计器共用同一份（原先各自 new HashSet<>(symptomTerms()) 一遍）
        Set<String> symptomDict = new HashSet<>(symptomTerms(dicts));
        vo.setUnmatched(unmatched(rows, symptomDict));
        vo.setNormalizable(normalizableRate(rows, symptomDict));
        vo.setScore(scoreDistribution(rows));
        vo.setQc(qcCoverage(rows));
        vo.setDataset(datasetShape(rows));
        vo.setDisclaimer(DISCLAIMER);
        vo.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        // 批次14 · 对标 A5：数字的血缘 —— 本报告出自哪一版词典。
        // 与 generatedAt 配对：「按什么算的」+「什么时候算的」才是可追溯的；
        // 只给时间的话，换过词表后数字变了也说不清是哪一版造成的。
        vo.setSourceVersion(termStore.effectiveDictVersion(ctx.orgId()));
        return vo;
    }

    /**
     * 报告行的扁平中间结构（A1/T2）：每条病历的 {@code structured_data} 与 {@code qc_results}
     * <b>各只解析一次</b>，各统计器共用这一份。
     *
     * <p>刻意<b>不</b>建 {@code Map<recordId, Map<String,Object>>} 那种请求内解析缓存 ——
     * 那只是把同一份解析结果换个容器多存一遍，省不掉解析，还多一次哈希查找。</p>
     *
     * <p>{@code symptoms} 缺失时归一成空列表，调用方无需再判空。</p>
     */
    static final class Row {
        final LocalDateTime visitTime;
        final Integer score;
        final LocalDateTime updateTime;
        final String chiefComplaint;
        final String selfReport;
        final Map<String, Object> structured;
        final Map<String, Object> qc;
        final List<?> symptoms;

        Row(Record r, Map<String, Object> structured, Map<String, Object> qc) {
            this.visitTime = r.getVisitTime();
            this.score = r.getScore();
            this.updateTime = r.getUpdateTime();
            this.chiefComplaint = r.getChiefComplaint();
            this.selfReport = r.getSelfReport();
            this.structured = structured;
            this.qc = qc;
            Object sym = structured == null ? null : structured.get("symptoms");
            this.symptoms = sym instanceof List<?> l ? l : List.of();
        }
    }

    /**
     * 按接诊时间过滤。
     *
     *
     * 必须上下界同时给才生效，只给一端按「未给」处理 ——
     *
     * 与 StatsController 的区间口径一致。只给一端会被理解成「从某时到最新」，
     * 那不是用户的意思；而如果只给一端就悄悄生效，用户看到记录数变少却找不到原因。
     */
    private List<Row> filterByVisitTime(List<Row> all, String start, String end) {
        // 两个条件同时成立才过滤：早前写成「任一非空即过滤」，
        // 结果只给 start 时返回 131 条（应为全部 500），与注释和既有口径都不符
        if (!notBlankDate(start) || !notBlankDate(end)) {
            return all;
        }
        LocalDate from = LocalDate.parse(start);
        LocalDate to = LocalDate.parse(end).plusDays(1);
        List<Row> out = new ArrayList<>();
        for (Row row : all) {
            LocalDateTime t = row.visitTime;
            if (t == null) {
                continue;
            }
            LocalDate d = t.toLocalDate();
            if (d.isBefore(from) || !d.isBefore(to)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    private boolean notBlankDate(String s) {
        return s != null && !s.isBlank();
    }

    /** 单端区间 → FiltersDTO：只有上下界同时给才落到 dateRange，只给一端按「未给」处理 */
    private FiltersDTO dateRangeFilters(String start, String end) {
        FiltersDTO f = new FiltersDTO();
        if (notBlankDate(start) && notBlankDate(end)) {
            f.setDateRange(List.of(start, end));
        }
        return f;
    }

    /** 取区间的一端；两端不齐（含只给一端）一律按「未给」返回 null。接诊时间与导入时间共用 */
    private String rangeBound(List<String> range, boolean start) {
        if (range == null || range.size() != 2
                || !notBlankDate(range.get(0)) || !notBlankDate(range.get(1))) {
            return null;
        }
        return start ? range.get(0) : range.get(1);
    }

    /**
     * 复制一份去掉**接诊时间**区间的范围：该维度由 Java 侧过滤（要算 excluded），
     * 不能与 SQL 重复；导入时间（{@code importRange}）刻意保留 —— 它下推到
     * {@code RecordFilter} 的 {@code create_time} 条件，与病历列表 / Qc 页同口径。
     * 证候（{@code pattern}）刻意<b>不</b>复制 —— 见 {@link #report(FiltersDTO)} 的说明。
     */
    private FiltersDTO withoutVisitRange(FiltersDTO f) {
        FiltersDTO copy = new FiltersDTO();
        copy.setDepartment(f.getDepartment());
        copy.setGrade(f.getGrade());
        copy.setIds(f.getIds());
        copy.setOnlyUnparsed(f.getOnlyUnparsed());
        copy.setImportRange(f.getImportRange());
        return copy;
    }

    private StandardizationReportVO.TimeRange rangeOf(List<Row> all, List<Row> picked,
                                                     String start, String end,
                                                     String importStart, String importEnd) {
        StandardizationReportVO.TimeRange r = new StandardizationReportVO.TimeRange();
        // 只有两端都给、过滤真正生效时才回显区间。
        // 只给一端时过滤没生效，若还回显「2024-01-01 ~ 不限」，界面会显示成一个
        // 实际并未应用的区间，而记录数其实是全部 —— 那是自相矛盾的展示。
        // rangeBound 已保证「不齐即 null」，这里直接透传。
        r.setStart(start);
        r.setEnd(end);
        r.setImportStart(importStart);
        r.setImportEnd(importEnd);
        r.setRecords(picked.size());
        r.setExcluded(all.size() - picked.size());
        return r;
    }

    /**
     * 按接诊月份分组。
     *
     *
     * 为什么要按月而不是按年：词表补一批、解析重跑一批，通常只覆盖某些月份的数据；
     *
     * 按月才能看出「哪些月份已经吃到新词表、哪些还没」。没有 visitTime 的病历
     * 归入「未知」一组，不静默丢弃。
     */
    private List<StandardizationReportVO.MonthlyBucket> byMonth(List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = row.visitTime == null
                    ? "未知"
                    : YearMonth.from(row.visitTime).toString();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<StandardizationReportVO.MonthlyBucket> out = new ArrayList<>();
        for (Map.Entry<String, List<Row>> en : groups.entrySet()) {
            List<Row> rs = en.getValue();
            StandardizationReportVO.MonthlyBucket b = new StandardizationReportVO.MonthlyBucket();
            b.setMonth(en.getKey());
            b.setRecords(rs.size());

            int symTotal = 0;
            int symHit = 0;
            for (Row row : rs) {
                for (Object o : row.symptoms) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    symTotal++;
                    if (m.get("normLevel") != null) {
                        symHit++;
                    } else {
                        String content = str(m.get("content"));
                        if (content.isEmpty()) {
                            content = str(m.get("sourceText"));
                        }
                        if (normalizableTerm(content)) {
                            b.setDictionaryGap(b.getDictionaryGap() + 1);
                        }
                    }
                }
            }
            b.setSymptomRate(symTotal == 0 ? null : pct(symHit, symTotal));

            // 计算月度评分统计（避免每月都重新跑整桶 scoreDistribution）
            int mCount = 0;
            int mCapped = 0;
            double mSum = 0.0;
            Integer mMin = null;
            Integer mMax = null;
            int mCappedThreshold = 10; // scoreDistribution 用的阈值：<=10 视为封顶
            for (Row row : rs) {
                Integer s = row.score;
                if (s == null) {
                    continue;
                }
                mCount++;
                if (s <= mCappedThreshold) {
                    mCapped++;
                }
                mSum += s;
                if (mMin == null || s < mMin) {
                    mMin = s;
                }
                if (mMax == null || s > mMax) {
                    mMax = s;
                }
            }
            // avgScore 是基本类型 double：空桶给 0.0（与原 scoreDistribution 的默认一致），
            // 不能给 null —— 三元里混入 null 会把表达式提升成 Double，setAvgScore 拆箱即 NPE
            b.setAvgScore(mCount == 0 ? 0.0 : Math.round(mSum * 10.0 / mCount) / 10.0);
            b.setCapped(mCapped);
            out.add(b);
        }
        // 「未知」永远排最后；其余按月份倒序（最近的在最前）
        out.sort(Comparator.comparing((StandardizationReportVO.MonthlyBucket b) -> b.getMonth())
                .reversed());
        out.sort(Comparator.comparing(b -> "未知".equals(b.getMonth())));
        return out;
    }

    /** 该未归一实体是否属于「本该能归一」的（不是碎片、不是错放） */
    private boolean normalizableTerm(String content) {
        if (content.isEmpty() || content.length() <= FRAGMENT_MAX_LEN) {
            return false;
        }
        return !isMisrouted(content) && !containsAny(content, PHYSICAL_SIGN);
    }

    private String pct(int part, int total) {
        return total == 0 ? null
                : Math.round(part * 1000.0 / total) / 10.0 + "%";
    }

    // ------------------------------------------------------------ 甲类 · 词典质量

    /** 各类型词表的规模、别名质量 */
    private List<StandardizationReportVO.DictQuality> dictQuality(Map<String, List<TermEntry>> dicts) {
        List<StandardizationReportVO.DictQuality> out = new ArrayList<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            if (!t.dict()) {
                continue;
            }
            List<TermEntry> entries = dicts.getOrDefault(t.key(), List.of());
            StandardizationReportVO.DictQuality q = new StandardizationReportVO.DictQuality();
            q.setType(t.key());
            q.setLabel(t.label());
            q.setTermCount(entries.size());
            int aliased = 0;
            int selfAlias = 0;
            for (TermEntry e : entries) {
                if (e.getAliases() != null && !e.getAliases().isEmpty()) {
                    aliased++;
                    // 别名与标准词相同会在归一时自命中，属数据缺陷
                    if (e.getAliases().contains(e.getStandardTerm())) {
                        selfAlias++;
                    }
                }
            }
            q.setAliasedCount(aliased);
            q.setSelfAliasCount(selfAlias);
            out.add(q);
        }
        return out;
    }

    /** 跨类重名：同一术语出现在多本词典里，类型判定会有歧义 */
    private List<String> crossTypeDuplicates(Map<String, List<TermEntry>> dicts) {
        Map<String, Set<String>> termToTypes = new LinkedHashMap<>();
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            if (!t.dict()) {
                continue;
            }
            for (TermEntry e : dicts.getOrDefault(t.key(), List.of())) {
                termToTypes.computeIfAbsent(e.getStandardTerm(), k -> new LinkedHashSet<>()).add(t.key());
            }
        }
        List<String> dup = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : termToTypes.entrySet()) {
            if (e.getValue().size() > 1) {
                dup.add(e.getKey());
            }
        }
        return dup;
    }

    // ------------------------------------------------------------ 乙类 · 数据集覆盖

    /**
     * 数据域内的病历（管理员看全部，其余只看本组织），带列投影（C1）。
     *
     * <p>没有投影时 {@code selectList} 会把 {@code records} 全部列拉回来 —— 21 个 TEXT + 2 个 JSON，
     * 4 万条约 45MB 常驻堆。这里只取本报告真正读到的 8 列（含主键 {@code id}）；
     * {@code present_illness} 等大 TEXT、以及本报告不消费的 {@code pattern} / {@code department} 一律不要。</p>
     *
     * <p>⚠️ 列名写错只会在运行期报 {@code Unknown column}，测试全绿 ——
     * 故下面这行 {@code .select(...)} 的字面量已纳入 {@code RecordColumnNameGuardTest} 守卫，
     * 且刻意内联书写（不传数组变量）：守卫按源码里的引号 token 逐个比对真实列清单，
     * 传变量会让它一个都扫不到，守卫就形同虚设。</p>
     */
    private List<Row> rowsInDomain(ReportContext ctx, FiltersDTO filters) {
        // 性能审查 P0-1：这里**不要** ORDER BY —— 报告随后按 byMonth(TreeMap)/coverage 等
        // 与行序无关的方式重排，带排序只会让「导入时间范围」这类条件退化成全表读整行 + filesort
        // （buildForAggregate 与 build 共用同一段 WHERE 组装，只是不追加排序键）。
        // 数据域与「看全部」走 ctx 快照（而非现读 RequestContextHolder）—— 见 ReportContext：
        // 本方法可能在 SWR 的后台重算线程里执行，那里读不到请求上下文。
        QueryWrapper<Record> wrapper = RecordFilter.buildForAggregate(
                ctx.domainOrgId(), filters, ctx.viewAllOrgs());
        wrapper.select("id", "visit_time", "score", "update_time", "chief_complaint", "self_report", "structured_data", "qc_results");
        return toRows(recordMapper.selectList(wrapper));
    }

    /**
     * 病历 → 报告行（A1/T2 的解析入口）：每条只解析一次 {@code structured_data} 与 {@code qc_results}。
     *
     * <p>包级可见是为了让「解析一次」能被测试直接钉住 —— 生产路径与测试走同一个入口，
     * 不会出现「测试测的是一套、线上跑的是另一套」。</p>
     */
    List<Row> toRows(List<Record> records) {
        List<Row> rows = new ArrayList<>(records.size());
        for (Record r : records) {
            rows.add(new Row(r, parseJson(r.getStructuredData()), parseJson(r.getQcResults())));
        }
        return rows;
    }

    /** 各类实体：抽取数 / 已归一数 */
    private List<StandardizationReportVO.TypeCoverage> coverage(List<Row> rows,
                                                                Map<String, List<TermEntry>> dicts) {
        Map<String, StandardizationReportVO.TypeCoverage> byField = new LinkedHashMap<>();
        // 只列有词典的类型：无词典的类型（如 cause）归一率恒为 0，画到图上就是一根没有意义的
        // 空条（表现为「有病因却没有对应术语库」）。与 dictQuality() / crossTypeDuplicates()
        // 的过滤口径保持一致 —— 那两个方法本就有 if (!t.dict()) continue;
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            if (!t.dict()) {
                continue;
            }
            StandardizationReportVO.TypeCoverage c = new StandardizationReportVO.TypeCoverage();
            c.setField(t.structuredKey());
            c.setLabel(t.label());
            byField.put(t.structuredKey(), c);
        }
        for (Row row : rows) {
            if (row.structured == null) {
                continue;
            }
            byField.values().forEach(c -> countEntities(c, row.structured.get(c.getField())));
        }
        // 标出「解析早于词表建立」的类型：词表非空且抽到了实体，却一条都没归上。
        // 这一种补词表无效，只能重跑解析 —— 与真正的词表缺口必须分开说。
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            StandardizationReportVO.TypeCoverage c = byField.get(t.structuredKey());
            if (c == null) {
                continue;
            }
            if (t.dict()) {
                c.setTermCount(dicts.getOrDefault(t.key(), List.of()).size());
            }
            c.setSuspectedStaleExtraction(c.getTermCount() > 0 && c.getTotal() > 0 && c.getNormalized() == 0);
        }
        return new ArrayList<>(byField.values());
    }

    @SuppressWarnings("unchecked")
    private void countEntities(StandardizationReportVO.TypeCoverage c, Object raw) {
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            c.setTotal(c.getTotal() + 1);
            if (m.get("normLevel") != null) {
                c.setNormalized(c.getNormalized() + 1);
            }
        }
    }

    /**
     * 症状未归一实体的四类构成；责任方不同，不能合并看。
     *
     *
     * 判定顺序要紧：具名错放先于长度判定。像「压痛」这种体征只有 2 个字，
     *
     * 若先按「长度 ≤ 2 = 抽取碎片」判，它会被归到碎片里 —— 但它其实是被完整抽出来的，
     * 只是被放错了数组。归错责会让修复方向跑偏（去改抽取截断，而不是改分类路由）。
     */
    private StandardizationReportVO.UnmatchedBreakdown unmatched(List<Row> rows, Set<String> symptomDict) {
        StandardizationReportVO.UnmatchedBreakdown b = new StandardizationReportVO.UnmatchedBreakdown();
        // 批次2：同一次遍历内累积「词表缺口」项的次数（键=实体原文），不额外扫库
        java.util.Map<String, Integer> gapCount = new java.util.LinkedHashMap<>();
        // G3：L2/L3 推断命中的实体（同一次遍历累积，键=原文，值=次数）
        java.util.Map<String, Integer> inferred = new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            for (Object item : row.symptoms) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Object levelObj = m.get("normLevel");
                if (levelObj != null) {
                    // G3：命中的实体不进「词表缺口」，但 L2（包含）/ L3（模糊）是推断出来的，
                    //     单列到 inferred 供人工确认 / 登记别名；L1 精确命中无需确认。
                    int level = (levelObj instanceof Number n) ? n.intValue() : 0;
                    if (level == com.tcm.ehr.common.utils.EsTermNormalizer.LEVEL_CONTAIN
                            || level == com.tcm.ehr.common.utils.EsTermNormalizer.LEVEL_FUZZY) {
                        String c = str(m.get("content"));
                        if (c.isEmpty()) {
                            c = str(m.get("sourceText"));
                        }
                        if (!c.isEmpty()) {
                            inferred.merge(c, 1, Integer::sum);
                        }
                    }
                    continue;
                }
                String content = str(m.get("content"));
                if (content.isEmpty()) {
                    content = str(m.get("sourceText"));
                }
                b.setTotal(b.getTotal() + 1);
                // ① 具名错放优先：含体征关键字，或判为脉/舌错放
                boolean namedMisroute = containsAny(content, PHYSICAL_SIGN)
                        || isMisrouted(content);
                if (namedMisroute) {
                    if (containsAny(content, PHYSICAL_SIGN)) {
                        b.setPhysicalSign(b.getPhysicalSign() + 1);
                    } else {
                        b.setMisrouted(b.getMisrouted() + 1);
                    }
                } else if (content.length() <= FRAGMENT_MAX_LEN) {
                    // ② 长度 ≤ 2 的未归一实体。
                    //    ⚠️ 这一桶**不是**「抽取截断的残字」—— 2026-10-15 实测：36739 条里
                    //    单字 0 条、全部 2 字，且每条的 content 与 sourceText 长度完全相等
                    //    （没有截断痕迹）；去重后 2642 个词一个都不在症状词典，高频项是
                    //    鼻塞(1473)、咽痛(949)、咽痒(806)、腰酸(780) 这类完整规范的症状词。
                    //    ⇒ 主体是**词表缺口**（补词即可归一），低频长尾里才混着真残字。
                    //    按词频硬切两桶不可靠（阈值 3 时残字桶里仍有 遗精/厌食/气促 这类正常词），
                    //    故保持单桶，由前端如实描述两段构成。
                    b.setFragment(b.getFragment() + 1);
                } else {
                    // ③ 多为标准词但词表没有 → 词表侧
                    b.setDictionaryGap(b.getDictionaryGap() + 1);
                    gapCount.merge(content, 1, Integer::sum);
                }
            }
        }
        // 批次2：按次数降序装入（LinkedHashMap 保序），前端直接渲染为可行动清单
        gapCount.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .limit(15)
                .forEach(e -> b.getTop().put(e.getKey(), e.getValue()));
        // G3：推断命中同型降序装入（上限同 TopN），供「登记别名 / 人工确认」清单
        inferred.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .limit(15)
                .forEach(e -> b.getInferred().put(e.getKey(), e.getValue()));
        return b;
    }

    /**
     * 可归一实体归一率。
     *
     *
     * 分母只算「已抽取 + 非抽取缺陷 + 已在症状词表内」：词表里根本没有的标准词
     *
     * （如 神疲乏力）是词表缺口本身，算进分母会把「补词表能改善多少」这个信号抹掉。
     */
    private StandardizationReportVO.NormalizableRate normalizableRate(List<Row> rows, Set<String> symptomDict) {
        StandardizationReportVO.NormalizableRate r = new StandardizationReportVO.NormalizableRate();
        for (Row row : rows) {
            for (Object item : row.symptoms) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String content = str(m.get("content"));
                if (content.isEmpty()) {
                    continue;
                }
                boolean defect = isMisrouted(content)
                        || containsAny(content, PHYSICAL_SIGN)
                        || content.length() <= FRAGMENT_MAX_LEN;
                if (defect || !isInDict(content, symptomDict)) {
                    continue;
                }
                r.setDenominator(r.getDenominator() + 1);
                if (m.get("normLevel") != null) {
                    r.setNumerator(r.getNumerator() + 1);
                }
            }
        }
        return r;
    }

    /** 质控分数分布与封顶率；封顶率高说明评分失去区分度 */
    private StandardizationReportVO.ScoreDistribution scoreDistribution(List<Row> rows) {
        StandardizationReportVO.ScoreDistribution d = new StandardizationReportVO.ScoreDistribution();
        int sum = 0;
        int scored = 0;
        for (Row row : rows) {
            if (row.score == null) {
                continue;
            }
            d.setTotal(d.getTotal() + 1);
            sum += row.score;
            scored++;
            if (d.getMin() == 0 || row.score < d.getMin()) {
                d.setMin(row.score);
            }
            if (row.score > d.getMax()) {
                d.setMax(row.score);
            }
            // 术语未标准化扣满 5 分（QcRuleSet 的 cap）即封顶
            if (row.qc != null && deductionsCapped(row.qc)) {
                d.setCapped(d.getCapped() + 1);
            }
        }
        if (scored > 0) {
            d.setAvg(Math.round(sum * 10.0 / scored) / 10.0);
        }
        return d;
    }

    /**
     * 质控完成度。
     *
     *
     * 判据不是「有没有 qc_results」而是「qc_results 里的 score 与 records.score 是否一致」：
     *
     * 病历重新导入或重跑解析后，score 可能已变而 qc_results 还是上一次的结果，
     * 那种情况下报告里的分数分布与扣分构成全是旧口径。
     */
    private StandardizationReportVO.QcCoverage qcCoverage(List<Row> rows) {
        StandardizationReportVO.QcCoverage c = new StandardizationReportVO.QcCoverage();
        c.setTotal(rows.size());
        LocalDateTime latest = null;
        for (Row row : rows) {
            Map<String, Object> qc = row.qc;
            if (qc == null || row.score == null) {
                continue;
            }
            Object scoreInQc = qc.get("score");
            if (scoreInQc instanceof Number n && n.intValue() == row.score) {
                c.setScored(c.getScored() + 1);
                if (row.updateTime != null
                        && (latest == null || row.updateTime.isAfter(latest))) {
                    latest = row.updateTime;
                }
            }
        }
        c.setComplete(c.getTotal() > 0 && c.getScored() == c.getTotal());
        if (latest != null) {
            c.setLastScoredAt(latest.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }
        return c;
    }

    /** 数据集形态：模板塌缩度决定这批数据能不能代表真实病历 */
    private StandardizationReportVO.DatasetShape datasetShape(List<Row> rows) {
        StandardizationReportVO.DatasetShape s = new StandardizationReportVO.DatasetShape();
        s.setRecordCount(rows.size());
        Set<String> chiefTemplates = new HashSet<>();
        int colloquial = 0;
        for (Row row : rows) {
            String chief = row.chiefComplaint;
            if (chief != null && !chief.isBlank()) {
                // 主诉里的病程月数每条不同，去掉数字才能看出模板数。
                // 用 RegexCache 复用编译结果：这里对 4 万行逐行调用，String.replaceAll 每次都会
                // 重新 Pattern.compile("\\d+")（P2-1 的有界备忘把它压成一次编译）。
                chiefTemplates.add(RegexCache.of("\\d+").matcher(chief).replaceAll("N"));
            }
            if (hasColloquialUnmatchedSymptom(row)) {
                colloquial++;
            }
        }
        s.setChiefComplaintTemplates(chiefTemplates.size());
        s.setRecordsWithColloquialSymptom(colloquial);
        return s;
    }

    /** 症状归一失败的实体，其原文出现在自述字段 → 来自患者口语，标准词表本就不收 */
    private boolean hasColloquialUnmatchedSymptom(Row row) {
        String selfReport = row.selfReport;
        if (selfReport == null || selfReport.isBlank()) {
            return false;
        }
        for (Object item : row.symptoms) {
            if (!(item instanceof Map<?, ?> m) || m.get("normLevel") != null) {
                continue;
            }
            String sourceText = str(m.get("sourceText"));
            if (!sourceText.isEmpty() && selfReport.contains(sourceText)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 工具

    private Set<String> symptomTerms(Map<String, List<TermEntry>> dicts) {
        Set<String> terms = new HashSet<>();
        for (TermEntry e : dicts.getOrDefault("symptom", List.of())) {
            terms.add(e.getStandardTerm());
            if (e.getAliases() != null) {
                terms.addAll(e.getAliases());
            }
        }
        return terms;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            // 单条脏数据不该让整份报告挂掉；计数上它会因为 parsed==null 被自然跳过
            log.debug("[报告] 解析 JSON 失败，跳过该条: {}", e.getMessage());
            return null;
        }
    }

    /** 术语未标准化扣分是否撞到 cap（QcRuleSet.standardization.cap = 5） */
    @SuppressWarnings("unchecked")
    private boolean deductionsCapped(Map<String, Object> qc) {
        if (!(qc.get("deductions") instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m
                    && "术语未标准化".equals(m.get("type"))
                    && m.get("points") instanceof Number n
                    && n.doubleValue() >= 5.0) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String text, String[] prefixes) {
        for (String p : prefixes) {
            if (text.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 文本是否包含任一关键字（用于体征这类「以…痛」形式出现的错放） */
    private boolean containsAny(String text, String[] keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInDict(String content, Set<String> dict) {
        return dict.contains(content);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}