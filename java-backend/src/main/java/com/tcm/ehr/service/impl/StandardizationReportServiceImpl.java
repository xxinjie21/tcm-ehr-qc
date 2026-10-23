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
     * <p>五个都是实测踩出来的坑（首字撞上，语义却属症状域）：</p>
     * <ul>
     *   <li><b>关节</b> —— 关节痛 / 关节怕冷 / 关节屈伸不利 …… 实测症状里有 90+
     *       个不同写法（关节痛 一条 164 次），是量最大的一类；</li>
     *   <li><b>尺肤</b> —— 尺部皮肤，诊断体征（尺肤热 / 尺肤凉 / 尺肤粘），
     *       与脉象是两回事；</li>
     *   <li><b>苔藓</b> —— 皮肤病（苔藓 / 苔藓样皮损），不是舌苔；</li>
     *   <li><b>尖锐</b> —— 声音 / 湿疣等义（尖锐声），脉象里没有「尖锐」；</li>
     *   <li><b>脉下</b> —— 脉位下方的部位描述（左脉下有一囊肿），是症状不是脉象。</li>
     * </ul>
     * <p>误判会把真症状判成「不可归一」：症状归一率虚高、错放数虚高，
     * 两边都是假信号。口径与 Python 侧 {@code _PT_BLACKLIST} 完全一致。</p>
     */
    private static final String[] MISROUTED_EXCLUDE = {"关节", "尺肤", "苔藓", "尖锐", "脉下"};
    /**
     * 方位 / 数量前缀：脉位常写成「两关偏浮」「右寸后关前」「双寸关」「左关偏浮」，
     * 首字不是三部，只判首字会漏（2026-10-19 实测存量这批 4318 条）。
     *
     * <p>只剥<b>一个</b>字符：既覆盖上述写法，又不会把「两手手指略自温」
     * 「左侧肩颈痠痛」这类症状误判成错放（剥掉首字后仍不以舌脉专名开头）。
     * 口径与 Python 侧 {@code _PT_QUALIFIERS} / 工具脚本同源。</p>
     */
    private static final String[] MISROUTED_QUALIFIER = {"左", "右", "双", "两"};

    /**
     * 该未归一症状实体是否是「脉象/舌象要素错放进了症状」。
     *
     * <p>与抽取侧 {@code _is_pulse_tongue} 同口径（都不限字数），
     * 只多一层保险：抽取侧靠第二字是否汉字挡噪声，统计侧靠
     * {@link #MISROUTED_EXCLUDE} 挡首字撞车的症状域真词。</p>
     */
    private boolean isMisrouted(String content) {
        String core = stripPtQualifier(content);
        // 黑名单在原字与剥前缀后都判：'左关节痛' 剥掉「左」后要能看到「关节」
        if (startsWithAny(content, MISROUTED_EXCLUDE) || startsWithAny(core, MISROUTED_EXCLUDE)
                || !startsWithAny(core, MISROUTED_PREFIX)) {
            return false;
        }
        // 含非汉字的抽取噪声（实测「关尺>寸」这类夹了比较符号的）不当舌脉，
        // 与抽取侧 _is_pulse_tongue 的汉字门同口径 —— 否则报告会把它算成错放，
        // 而抽取侧又不会去修，两边数字永远对不上。
        return content.chars().allMatch(ch -> ch >= 0x4e00 && ch <= 0x9fff);
    }
    /** 体征错放的关键字：属体征而非症状 */
    private static final String[] PHYSICAL_SIGN = {"压痛", "触痛", "叩痛", "反跳痛"};

    /**
     * 问诊整句的词根锚：以这些词开头的未归一实体属「问诊子项描述」
     * （大便日一行 / 小便色略黄 / 夜寐一般 / 饮水多 …），<b>不是症状词表缺口</b>。
     *
     * <p><b>为什么要在「最该先补」清单里排除它们</b>：实测 dictionaryGap 桶 116127 条里，
     * 问诊整句的频次远高于真实症状缺口 —— 前 8 名被「大便日一行(469)、大便成形(410)、
     * 大便一日一次(352)、大便日(235)、小便色略黄(216)…」占满，而真正该补的症状词
     * （如「上下楼梯特别疼」50 次）排在 84 名之后，永远不会出现在只取前 8 的待办里。
     * 把它们推荐给用户「补进症状词典」是<b>错误方向</b>：那是抽取边界问题（应由
     * python 侧 inquiry 抽离解决），补成症状标准词会污染所有含该子串的实体。</p>
     *
     * <p>口径与 {@code python-nlp/nlp_rules.py#_INQUIRY_LEAD} 同源（方案A），
     * 但此处<b>只用于报告排序过滤、不改数据</b>，故取更长的一组以免误伤真症状词。</p>
     */
    private static final String[] INQUIRY_LEAD = {
            "大便", "小便", "二便", "排便", "夜寐", "睡眠", "入睡", "饮食", "食欲", "饮水",
            "食量", "纳食", "胃口", "精神", "面色", "乏力", "倦怠", "困倦",
            // 时段前缀 + 问诊词（晨起小便淡黄 / 夜间明显）：剥掉时段后仍以问诊词开头
            "晨起", "晨间", "清晨", "夜间", "夜里", "睡前", "醒后", "饭后", "食后", "平素",
            // 喜恶（喜趴睡 / 喜肉食 / 喜甜食 / 喜叹息）：问诊十问之「问喜恶」
            "喜"};
    /**
     * 病程 / 诱因描述的开头（活动后缓解 / 休息后缓解 / 遇劳加重 / 受凉后…）。
     *
     * <p>2026-10-21 新增，用于把这类实体从「词表缺口」里拆出来单列。它们是
     * <strong>对症状的修饰</strong>（什么时候轻、什么时候重），不是症状本体；
     * 补进症状词表会让所有含该子串的实体被错误归并（如把「活动后缓解」收成标准词，
     * 会命中「活动后缓解不明显」等）。责任方是抽取侧，与「词表缺口」必须分开说。</p>
     *
     * <p>判定用 startsWith（前置于词首）而非 contains：这里列的都是完整词头
     * （「受凉后」「遇劳」「活动后」），用 contains 会误伤「不活动后」这类边缘串。</p>
     */
    private static final String[] PROCESS_DESC_LEAD = {
            "活动后", "活动时", "活动则", "休息后", "休息时", "劳累后", "劳累时", "劳则",
            "遇劳", "遇冷", "遇热", "遇风", "受凉后", "受寒后", "受风后",
            "进食后", "进餐后", "服药后", "饮水后", "大便后", "小便后",
            "持续性", "反复发作", "阵发性", "间歇性", "夜间明显", "晨起明显", "午后",
            "加重", "减轻", "缓解", "好转", "自行缓解"};

    /**
     * 脉诊手法 / 舌象要素被当成症状时的特征词（2026-10-21）。
     *
     * <p>正确归属是脉象 / 舌象字段，补进症状词表属错映射；且它们不以谓词开头，
     * 逃过了 INQUIRY_LEAD 与 PROCESS_DESC_LEAD，会占据「最该先补」前排
     * （实测 不受按 222 / 中取不受按 115 / 红点多 225）。</p>
     *
     * <p>用 contains 判定 —— 这些是词中/词尾的形态标记，不是词头。</p>
     */
    private static final String[] MISTYPE_MARKERS = {
            "不受按", "中取", "浮取", "沉取", "应指",
            "红点", "瘀点", "齿痕", "裂纹", "舌下", "边有"};

    /**
     * 问诊词根的扩展集（2026-10-21）。
     *
     * <p>INQUIRY_LEAD 只收了以谓词本身开头的情形；实测还有一批以「习惯性饮水」
     * 「控制饮食」「精力体力可」这类修饰/并列形式出现，同样是十问子项而非症状本体。</p>
     */
    private static final String[] INQUIRY_MARKERS = {"饮水", "饮食", "精力", "体力", "控制", "习惯性"};

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
        // 未归一构成：按<strong>责任方</strong>分列 —— 词表侧（补词表可改善）与抽取侧
        // （补词表无效，得改抽取边界）必须分开，否则「词表缺 N 条」会把抽取问题算进词表账。
        rows.add(List.of("未归一构成", "词表未收录", String.valueOf(u.getDictionaryGap()), "责任：词表"));
        rows.add(List.of("未归一构成", "问诊整句被当成症状", String.valueOf(u.getInquiryFragment()), "责任：抽取（该进 inquiry）"));
        rows.add(List.of("未归一构成", "病程/诱因描述", String.valueOf(u.getProcessDesc()), "责任：抽取（是修饰非症状）"));
        rows.add(List.of("未归一构成", "脉/舌要素被当成症状", String.valueOf(u.getMisrouted()), "责任：抽取（该进脉象/舌象字段）"));
        rows.add(List.of("未归一构成", "脉诊手法/舌象碎片", String.valueOf(u.getMistypeMarker()), "责任：抽取（本不该成为实体）"));
        rows.add(List.of("未归一构成", "体征被当成症状", String.valueOf(u.getPhysicalSign()), "责任：抽取"));
        // 措辞修正（2026-10-21）：原叫「抽取残词」是错的断言 —— 实测这 6542 条里单字 0 条、
        // 与 sourceText 等长（无截断痕迹），主体是词表未收录的二字症状词与属性词，
        // 不是「抽取截断的残字」。按实测构成改名。
        rows.add(List.of("未归一构成", "二字未归一实体", String.valueOf(u.getFragment()), "需人工研判口径"));
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
        // 归一率类统计（coverage / unmatched / normalizable）**只看质控合格的病历**：
        // 本页是「标准化质量报告」，衡量「进标准数据集的那批数据」的术语质量；
        // 待复核 / 无效病历本身数据就有问题（缺核心要素、逻辑冲突），算进分母会把
        // 「术语标准化质量」与「病历书写质量」混进同一根指标，用户无法据此判断该补词表还是修病历。
        // 与导出条件（grade=合格）对齐后，报告数字可直接解释数据集品质。
        // 分数分布 / qc 一致性 / 按月趋势仍用 rows —— 它们统计质控与数据本体，需全量。
        List<Row> qualified = qualifiedOnly(rows);
        vo.setCoverage(coverage(qualified, dicts));
        // A2：词表只读一次，两个统计器共用同一份（原先各自 new HashSet<>(symptomTerms()) 一遍）
        Set<String> symptomDict = new HashSet<>(symptomTerms(dicts));
        vo.setUnmatched(unmatched(qualified, symptomDict));
        vo.setNormalizable(normalizableRate(qualified, symptomDict));
        vo.setScore(scoreDistribution(rows));
        vo.setQc(qcCoverage(rows));
        vo.setDataset(datasetShape(qualified));
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
        /** 质控分级（合格/待复核/无效/null=未质控）；归一率类统计据此只看合格病历 */
        final String grade;

        Row(Record r, Map<String, Object> structured, Map<String, Object> qc) {
            this.visitTime = r.getVisitTime();
            this.score = r.getScore();
            this.updateTime = r.getUpdateTime();
            this.chiefComplaint = r.getChiefComplaint();
            this.selfReport = r.getSelfReport();
            this.structured = structured;
            this.qc = qc;
            this.grade = r.getGrade();
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
        // grade 必须投影：归一率类统计要按「质控合格」收窄（见 computeReport 的 qualifiedOnly），
        // 少了这一列 Row.grade 恒为 null，过滤后空集 —— 表现为报告归零。
        wrapper.select("id", "grade", "visit_time", "score", "update_time", "chief_complaint", "self_report", "structured_data", "qc_results");
        return toRows(recordMapper.selectList(wrapper));
    }

    /**
     * 病历 → 报告行（A1/T2 的解析入口）：每条只解析一次 {@code structured_data} 与 {@code qc_results}。
     *
     * <p>包级可见是为了让「解析一次」能被测试直接钉住 —— 生产路径与测试走同一个入口，
     * 不会出现「测试测的是一套、线上跑的是另一套」。</p>
     */
    /**
     * 只保留质控合格的病历（用于归一率类统计）。grade 为 null（未质控）也排除 ——
     * 它们还没进入标准化数据集。
     */
    private static List<Row> qualifiedOnly(List<Row> rows) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if ("合格".equals(r.grade)) {
                out.add(r);
            }
        }
        return out;
    }

    List<Row> toRows(List<Record> records) {
        List<Row> rows = new ArrayList<>(records.size());
        for (Record r : records) {
            rows.add(new Row(r, parseJson(r.getStructuredData()), parseJson(r.getQcResults())));
        }
        return rows;
    }

    /**
     * 「解析早于词表」判定的最小样本量。
     *
     * <p>判据是「有词表、抽到实体、却一条都没归一」，但样本极少时该推断不成立：
     * 治法只抽到 1 条（正则误匹配「治法后缓解」）就被判成「解析早于词表」，
     * 会让用户以为重跑解析没生效。样本不足时不下「陈旧」结论。</p>
     */
    private static final int STALE_MIN_SAMPLE = 10;

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
            // 加最小样本量门槛（2026-10-21）：判据原为「有词表 + 抽到实体 + 一条都没归一」，
            // 但样本量极小时推断不成立 —— 实测治法只抽到 1 条实体（且是「治法后缓解」这种
            // 正则误匹配，本来就归不上），却判成「解析早于词表」，让用户以为白重跑了解析。
            // 低于门槛时不列为「陈旧」，前端按 total 展示「样本过少，暂不判定」。
            c.setSuspectedStaleExtraction(c.getTermCount() > 0 && c.getTotal() >= STALE_MIN_SAMPLE
                    && c.getNormalized() == 0);
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
            // 判据必须与 QcScorer.countUnnormalized 保持一致（当前都是「normLevel != null 即已归一」）。
            // 质控扣分与报告展示是两套实现，若一方改动判空口径（如排除 LEVEL_FUZZY、把 0 当未命中），
            // 用户就会看到「报告说未归一 N 条，扣分却按 M 条算」的矛盾。改动此处务必同步另一边。
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
        // 2026-10-21：各抽取侧桶也累积 TOP 词 —— 本页定位是给开发人员，
        // 只有「有 N 条」无从下手，必须给出「是哪 N 条」（改抽取规则要按词核对）。
        java.util.Map<String, Integer> inquiryTop = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> processTop = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> mistypeTop = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> fragmentTop = new java.util.LinkedHashMap<>();
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
                // 判定顺序（2026-10-21 修正，原实现有 bug）：
                // 语义判定必须全部前置，长度判定（<=2）放最后。
                // 旧顺序把长度判定排在「脉诊手法/舌象碎片」之前，导致同一类词被劈成两桶：
                // 「红点」(2 字) 进 fragment、「红点多」(3 字) 进 misrouted —— 于是
                // 报告上「脉舌错放」与实际内容对不上，用户看到的描述不真实。
                //
                // 责任划分（同为抽取侧，但修复方式不同，必须分开说）：
                //   physicalSign  体征（压痛）→ 该进症状但被标成体征词，属命名问题
                //   misrouted     脉/舌要素（脉细数/舌红）→ 该路由到 pulseList/tongueList
                //   mistypeMarker 脉诊手法/舌象碎片（不受按/红点多）→ 本不该成为实体
                //   inquiry       十问子项（大便日一行/入睡转快）→ 该进 inquiry 字段
                //   processDesc   病程诱因（活动后缓解）→ 是修饰，不该成为实体
                if (containsAny(content, PHYSICAL_SIGN)) {
                    b.setPhysicalSign(b.getPhysicalSign() + 1);
                } else if (isMisrouted(content)) {
                    b.setMisrouted(b.getMisrouted() + 1);
                } else if (containsAny(content, MISTYPE_MARKERS)) {
                    b.setMistypeMarker(b.getMistypeMarker() + 1);
                    mistypeTop.merge(content, 1, Integer::sum);
                } else if (startsWithAny(content, INQUIRY_LEAD)
                        || containsAny(content, INQUIRY_MARKERS)) {
                    // 问诊整句的正确去向是抽取侧的 inquiry 字段（十问子项），
                    // 补成症状标准词属错映射 —— 算进「词表缺口」会让报告声称
                    // 「词表缺 N 条」，而补词表根本解决不了这批。
                    b.setInquiryFragment(b.getInquiryFragment() + 1);
                    inquiryTop.merge(content, 1, Integer::sum);
                } else if (startsWithAny(content, PROCESS_DESC_LEAD)) {
                    // 病程 / 诱因是症状的修饰而非本体，没有独立标准词可补。
                    // 判定在问诊之后 —— 「夜寐俯卧」这类同时含两类前缀的归问诊更准确。
                    b.setProcessDesc(b.getProcessDesc() + 1);
                    processTop.merge(content, 1, Integer::sum);
                } else if (content.length() <= FRAGMENT_MAX_LEN) {
                    // 长度 <= 2 的未归一实体。
                    // ⚠️ 这一桶**不是**「抽取截断的残字」—— 实测单字 0 条、全部 2 字，
                    //    且 content 与 sourceText 长度相等（无截断痕迹）。
                    // 实测构成：否定式与属性词（不痛/偏黄）、时间场景（凌晨）、
                    //    真症状（咽喉/有梦）、动作（刷牙）—— 语义混杂，无法用单一规则拆净。
                    // 故保持单桶、如实描述；补词必须人工研判口径（把「偏黄」收成标准词
                    //  会污染所有含该子串的实体）。
                    b.setFragment(b.getFragment() + 1);
                    fragmentTop.merge(content, 1, Integer::sum);
                } else {
                    // 其余：多为标准词但词表没有 → 词表侧（补词表即可改善）
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
        // 2026-10-21：四个抽取侧桶的 TOP15（开发人员据此核对抽取规则）
        putTop(b.getInquiryFragmentTop(), inquiryTop);
        putTop(b.getProcessDescTop(), processTop);
        putTop(b.getMistypeMarkerTop(), mistypeTop);
        putTop(b.getFragmentTop(), fragmentTop);
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
        // ⚠️ 2026-10-21 口径修正（原实现恒 100%，无信息量）：
        // 原分母 = 「在症状词表里」的实体 —— 而「在词表里」与「已归一」是同一件事，
        // 于是分子恒等于分母，该指标永远是 100%，既衡量不了改进也无法据此判断。
        // 原测试的意图是对的（「词表里没有的不该算归一失败」），但落在「排除不在词表的」
        // 这条实现上就跑偏了 —— 词表缺口恰恰是最该被看到的失败。
        //
        // 新口径：分母 = 全部症状实体 − 抽取缺陷（脉舌错放 / 问诊整句 / 病程诱因），
        // 分子 = 其中已归一。
        //  - 词表为空时：分母 > 0、分子 = 0 ⇒ 0%（真实反映「没词表就归不了」）
        //  - 补词表后上升 ⇒ 有区分度，可用于验证词表建设的进展
        //  - 与 unmatched 的拆桶判据同源，两处口径不会打架
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
                // 抽取缺陷（不该算症状）一律不进分母 —— 与 unmatched 的拆桶顺序保持一致
                if (containsAny(content, PHYSICAL_SIGN)
                        || isMisrouted(content)
                        || containsAny(content, MISTYPE_MARKERS)
                        || startsWithAny(content, INQUIRY_LEAD)
                        || containsAny(content, INQUIRY_MARKERS)
                        || startsWithAny(content, PROCESS_DESC_LEAD)) {
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

    /**
     * 把计数表按次数降序装入前 15 项（各抽取侧桶的 TOP 清单）。
     *
     * <p>与 unmatched 里 top 的装入逻辑同型，抽出来避免四份重复代码。</p>
     */
    private static void putTop(java.util.Map<String, Integer> target,
                               java.util.Map<String, Integer> counter) {
        counter.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                .limit(15)
                .forEach(e -> target.put(e.getKey(), e.getValue()));
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
        c.setTotal(0);   // 按「可质控病历」计数，见下方循环
        LocalDateTime latest = null;
        for (Row row : rows) {
            if (row.grade == null) {
                continue;   // 无可质控内容，不计入分母（否则 complete 永远不成立）
            }
            c.setTotal(c.getTotal() + 1);
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

    /**     * 数据集形态：模板塌缩度决定这批数据能不能代表真实病历。     *     * <p>入参是<b>质控合格</b>的那一份（qualified）：本项描述的是「标准数据集」的形态，     * 而导出条件就是 grade=合格 —— 用全部病历会让 recordCount 与「可导出数据集」对不上，     * 用户按这个数字估算导出规模会偏大（实测 34182 vs 28793）。</p>     */
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

    /**
     * 剥掉一个可选的方位/数量前缀（左/右/双/两）后返回，供 {@link #isMisrouted} 的首字判据使用。
     * 空串或长度不足时原样返回 —— 单字「两」本身剥不出舌脉专名，交给后续判据拒绝。
     */
    private String stripPtQualifier(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() > 1 && startsWithAny(content, MISROUTED_QUALIFIER)) {
            return content.substring(1);
        }
        return content;
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