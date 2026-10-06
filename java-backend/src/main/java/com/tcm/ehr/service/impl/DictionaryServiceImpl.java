package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.utils.ExcelStreamReader;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.ImportResultVO;
import com.tcm.ehr.service.IDictionaryService;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 词典服务实现：导入（Excel/CSV/JSON -> JSON -> 内存+ES）、查询、回滚、备份列表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryServiceImpl implements IDictionaryService {

    /** 单个词典文件大小上限（字节）。与 Spring multipart.max-file-size 同值（50MB） */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    /** 单次导入的词条数上限。文件大小上限挡不住「行数极多的小文件」：
     *  合并进内存、逐条 merge、再整表替换 + 重建该组织 ES 索引，代价随行数线性上升。 */
    static final int MAX_ROWS = 50000;


    private final IEsTermIndexService esTermIndexService;
    private final com.tcm.ehr.common.utils.DistLock distLock;
    private final com.tcm.ehr.service.DictionaryTermStore termStore;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 术语查询：读词典 JSON，按关键字对标准词与别名做包含匹配。
     *
     * 每次调用都直接读文件 —— 本方法只服务三处：术语词典页列表 / 输入联想 /
     *
     * 质控规则「期望值」下拉，都是低频请求，5 类词条合计三千余条、读文件代价可忽略。
     *
     * 两种返回模式（由 page 决定）：
     *
     *   - page <= 0（不分页）：返回全部命中词条。供质控规则下拉与
     *       输入联想使用 —— 两者都需要完整候选集才能选到任意术语。
     *   - page > 0（分页）：只返回第 page 页（每页 size 条），
     *       供术语词典页翻页浏览。分页切片对越界做了双向夹取，页码超出范围返回空列表
     *       而非抛异常（subList 越界会抛 IndexOutOfBounds）。
     *
     * @param type    词典类型
     * @param keyword 搜索关键字，可为空（表示不过滤）
     * @param page    页码，从 1 开始；<= 0 表示不分页、返回全部命中
     * @param size    每页条数，仅 page > 0 时生效
     * @return 命中词条视图：terms（standardTerm / aliases）+ total（命中总数）
     * @throws IOException 词典文件读取失败
     */
    public Map<String, Object> searchTerms(String type, String keyword, int page, int size)
            throws IOException {
        // 1. 读「当前组织生效」的词条：有自有词条读自己的，否则回退基础层
        String orgId = RequestUtils.currentOrgId();
        List<TermEntry> entries = termStore.readEffective(orgId, type);
        List<Map<String, Object>> hit = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim();
        // 2. 逐条比对标准术语与别名，任一命中即算命中（不过滤时 kw 为空，全量收）
        for (TermEntry e : entries) {
            boolean matched = kw.isEmpty()
                    || e.getStandardTerm().contains(kw)
                    || (e.getAliases() != null && e.getAliases().stream().anyMatch(a -> a.contains(kw)));
if (matched) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("standardTerm", e.getStandardTerm());
                    m.put("aliases", e.getAliases());
                    // 批次 22：带上编码，词典页与导出才能显示国标码。
                    // 空串归 null 与 normalize 同口径：区分「没有编码」与「编码是空串」。
                    String code = e.getCode();
                    m.put("code", code == null || code.isBlank() ? null : code);
                    hit.add(m);
                }
        }
        // 3. 分页切片：from/to 双向夹取，页码超出总页数时返回空列表而不是抛越界异常
        List<Map<String, Object>> terms = hit;
        if (page > 0) {
            int step = Math.max(1, size);
            int from = Math.min((page - 1) * step, hit.size());
            int to = Math.min(from + step, hit.size());
            terms = hit.subList(from, to);
        }
        // 4. total 一律是命中总数：分页时供词典页算总页数，不分页时一并返回供调用方判断
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terms", terms);
        out.put("total", hit.size());
        return out;
    }

    // ---------------------------------------------------------------- 导入

    @Override
    /**
     * 词典导入：解析上传文件 -> 与现有词典合并去重 -> 备份 -> 覆盖写文件 -> 全量重建 ES 索引。
     *
     * 按扩展名分流：JSON 走直传解析，Excel/CSV 走表格解析。合并以标准术语为键，
     *
     * 同词条合并别名并保留已有的 source / code。
     *
     * 索引重建失败会触发补偿：把文件退回导入前版本并尽力重建旧索引，之后仍抛异常 ——
     *
     * 避免出现「文件已更新、索引未建起」导致归一结果与词典页长期不一致。
     *
     * @param type 词典类型
     * @param file 上传文件（.json / .xlsx / .xls / .csv）
     * @return 导入结果（总数、成功数、失败数及逐行失败原因）
     * @throws IOException 文件解析或落盘失败；索引重建失败时也以此抛出
     * @throws IllegalArgumentException 文件格式不支持，或 JSON 结构非法
     */
    public ImportResultVO importDictionary(String type, MultipartFile file) throws IOException {
        return importDictionary(type, file, RequestUtils.currentOrgId());
    }

    @Override
    public java.util.List<TermEntry> currentTerms(String orgId, String type) {
        return termStore.read(orgId, type);
    }

    @Override
    public ParseResult parseTerms(String type, MultipartFile file) throws IOException {
        List<Map<String, Object>> failures = new ArrayList<>();
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "文件超过 50MB，请拆分后导入（Excel 解析需将整份文件载入内存）");
        }
        List<TermEntry> terms = fileName(file).endsWith(".json")
                ? parseJsonEntries(file, failures)
                : parseTabularEntries(type, file, failures);
        return new ParseResult(terms, failures);
    }

    /**
     * 导入到指定组织层（批次 17）。
     *
     * 原实现固定用 currentOrgId()，管理员无法写基础层；这里把落库目标
     *
     * 提为参数，orgId="" 即基础层。提案合并走的是 termStore.replace
     * 而不是本方法 —— 本方法带「合并语义 + 自动归档」，那是管理员直写专用通道。
     */
    @Override
    public ImportResultVO importDictionary(String type, MultipartFile file, String orgId)
            throws IOException {
        List<Map<String, Object>> failures = new ArrayList<>();
        // 0. 大小防护：Excel 走 WorkbookFactory 全量载入，xlsx 解压后可达压缩体积的
        //    数十倍，50MB 文件能把堆撑爆并**拖垮整个进程**（不只是这一个请求失败）。
        //    病历导入侧早有同款上限（RecordServiceImpl.MAX_FILE_BYTES），词典侧此前只判
        //    isEmpty()，是唯一没设防的 POI 入口 —— 这里补齐，两侧口径一致。
        //    与 Spring 的 multipart.max-file-size 同为 50MB，不额外收紧既有行为。
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "文件超过 50MB，请拆分后导入（Excel 解析需将整份文件载入内存）");
        }
        // 1. 按扩展名分流解析：JSON 直传解析，Excel/CSV 走表格解析
        List<TermEntry> incoming = fileName(file).endsWith(".json")
                ? parseJsonEntries(file, failures)
                : parseTabularEntries(type, file, failures);

        // 2. 合并：现有词典打底，新条目并入（同 standardTerm 合并别名，保留已有 source/code）
        //    ⚠️ 合并基数是「当前组织这一层」而不是「基础层」：组织导入不该把基础层词条
        //    复制成自己的私有词条 —— 那会让基础层以后的修订再也影响不到该组织。
        List<TermEntry> previous = termStore.read(orgId, type);
        Map<String, TermEntry> merged = new LinkedHashMap<>();
        for (TermEntry e : previous) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }
        for (TermEntry e : incoming) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }

        List<TermEntry> entries = new ArrayList<>(merged.values());
        // 3. 落库（同一事务里更新内容版本，避免「词条换了、版本没换」而跳过重建）
        //    原先此处先写一份 dictionary_backups 全量备份；该表已随批次 17 废弃
        //    （归档版本是「合并后」的快照而非「写前」备份），ES 重建失败的补偿
        //    直接用内存里的 previous 回退。
        // 3. 落库（同一事务里更新内容版本，避免「词条换了、版本没换」而跳过重建）
        // P1-9：行数上限 —— 在**落库前**兜住，并把「拆开导入」的下一步直接写给用户
        if (entries.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "词条数 " + entries.size() + " 超过单次上限 " + MAX_ROWS + " 条，请拆分后分批导入");
        }
        String contentVersion = termStore.replace(orgId, type, entries);
        // 5. 只重建「本组织」在 ES 里的文档；失败则退回库内容，绝不谎报已同步
        try {
            // 跨实例互斥（批次16）：rebuild 是「删该组织文档 + bulk 灌入」两步，
            // 两个实例同时做会交错成混合状态（一个删掉另一个刚灌的）。
            // 锁按「类型+组织」划分，不做全局单键；拿不到锁直接拒绝，不放行。
            distLock.runLocked(com.tcm.ehr.common.utils.DistLock.dictRebuildLock(type, orgId),
                    () -> {
                        try {
                            esTermIndexService.rebuild(type, orgId, entries, contentVersion);
                        } catch (IOException io) {
                            throw new java.io.UncheckedIOException(io);
                        }
                        return null;
                    });
            termStore.markIndexed(orgId, type, contentVersion);
        } catch (ConcurrentOperationException lockConflict) {
            // 拿不到跨实例锁：ES 从未被本请求碰过，只需把库内容退回导入前。
            // 不得走 compensateFailedRebuild —— 那里不带锁就重建索引，会和持锁实例交错，
            // 正是这把锁要防的场景。原样抛出，由全局出口映射为 409 与可操作文案。
            log.warn("[词典] {} (org={}) 有并发重建正在进行，已退回库内容: {}",
                    type, orgId, lockConflict.getMessage());
            try {
                termStore.replace(orgId, type, previous);
            } catch (Exception rollbackError) {
                log.error("[词典] {} (org={}) 并发冲突回退失败，库内容已变更且未记为已同步，"
                        + "下次启动对账会重建: {}", type, orgId, rollbackError.getMessage());
            }
            throw lockConflict;
        } catch (Exception e) {
            // DB 已改而 ES 没跟上 → 归一结果与词典页会长期不一致。退回库内容并尽力恢复索引。
            log.error("[词典] {} (org={}) ES 重建失败，回退库内容并尝试恢复索引: {}",
                    type, orgId, e.getMessage());
            compensateFailedRebuild(type, orgId, previous, contentVersion);
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("词典已回滚到导入前（ES 重建失败：" + e.getMessage() + "）", e);
        }

        // 5. 汇总导入结果：解析失败逐行带原因返回，不中断整体导入
        ImportResultVO vo = new ImportResultVO();
        vo.setType(type);
        vo.setTotal(incoming.size() + failures.size());
        vo.setImported(incoming.size());
        vo.setFailed(failures.size());
        vo.setFailures(failures);
        log.info("[词典] {} 导入完成: 新解析{}条(失败{}), 合并后共{}条",
                type, incoming.size(), failures.size(), entries.size());
        return vo;
    }

    /**
     * ES 重建失败后的补偿：把库内容退回导入前的版本，再尽力把索引也建回旧版本。
     *
     * 两步都可能再失败 —— 那时只记日志，不掩盖最初的异常（调用方会把它抛出去）。
     *
     * 无备份（首次导入、本组织原先没有词条）时用 previous 写回，通常是空列表。
     *
     * 关键：补偿路径不会把失败的那个版本记为已同步。记了就等于谎报，
     *
     * 启动对账会跳过重建，归一会永远停在这一版错误的数据上。
     */
    private void compensateFailedRebuild(String type, String orgId,
                                         List<TermEntry> previous, String failedVersion) {
        // 1. 先把库内容退回导入前（previous 即本次导入开始时读到的原内容；
        //    dictionary_backups 已随批次 17 废弃 —— 归档版本是「合并后」的快照，
        //    不是「写前」备份，补偿回退直接用内存里的 previous）
        try {
            termStore.replace(orgId, type, previous);
        } catch (Exception e) {
            // 库都退不回去就没必要再试索引，记日志交人工重新导入
            log.error("[词典] {} (org={}) 回退失败，库与索引可能不一致，需重新导入修复: {}",
                    type, orgId, e.getMessage());
            return;
        }
        // 2. 再尽力把索引建回旧版本；失败只记日志，不掩盖最初的异常。
        //    注意不要 markIndexed 那个失败版本 —— 那样启动对账会误判「已同步」。
        try {
            String oldVersion = termStore.contentVersion(previous);
            esTermIndexService.rebuild(type, orgId, previous, oldVersion);
            termStore.markIndexed(orgId, type, oldVersion);
            log.warn("[词典] {} (org={}) 已回滚到导入前并重建索引", type, orgId);
        } catch (Exception e) {
            log.error("[词典] {} (org={}) 索引恢复失败（该类术语的归一不可用，请重新导入；"
                            + "失败版本 {} 未记为已同步）: {}", type, orgId, failedVersion, e.getMessage());
        }
    }

    /** JSON 直传：TermEntry 数组 [{standardTerm, aliases[], source?, code?}] */
    private List<TermEntry> parseJsonEntries(MultipartFile file, List<Map<String, Object>> failures) throws IOException {
        List<TermEntry> parsed;
        // 1. 整个文件按 TermEntry 数组解析；结构不对直接报错，不做部分导入
        try {
            parsed = objectMapper.readValue(readTextAutoCharset(file), new TypeReference<List<TermEntry>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "JSON 解析失败：需为 TermEntry 数组，形如 [{\"standardTerm\":\"消渴\",\"aliases\":[\"消渴病\"],\"source\":\"...\",\"code\":\"...\"}]");
        }
        // 2. 逐条校验并规整，标准术语为空的记失败继续
        List<TermEntry> ok = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            TermEntry e = parsed.get(i);
            if (e == null || e.getStandardTerm() == null || e.getStandardTerm().isBlank()) {
                failures.add(Map.of("row", i + 1, "reason", "标准术语列为空"));
                continue;
            }
            ok.add(normalize(e));
        }
        return ok;
    }

    /** Excel(.xlsx/.xls) / CSV：标准术语 / 别名 / 代码（第 3 列可选） */
    private List<TermEntry> parseTabularEntries(String type, MultipartFile file,
                                                List<Map<String, Object>> failures) throws IOException {
        String name = fileName(file);
        List<String[]> rows;
        // 1. 按扩展名选解析器；都不匹配直接拒绝，避免把二进制当文本读
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            rows = readExcelRows(file);
        } else if (name.endsWith(".csv")) {
            rows = readCsvRows(file);
        } else {
            throw new IllegalArgumentException("文件格式不支持：仅支持 Excel(.xlsx/.xls)、CSV 或 JSON");
        }

        // 2. 逐行组装词条：标准术语为空记失败，别名按多种分隔符拆开
        List<TermEntry> ok = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String standard = row[0] == null ? "" : row[0].trim();
            if (standard.isEmpty()) {
                failures.add(Map.of("row", i + 1, "reason", "标准术语列为空"));
                continue;
            }
            List<String> aliases = new ArrayList<>();
            if (row[1] != null && !row[1].isBlank()) {
                for (String a : row[1].split("[、,，;；|]")) {
                    if (!a.isBlank()) aliases.add(a.trim());
                }
            }
            String code = row[2] == null || row[2].isBlank() ? null : row[2].trim();
            // 3. 缺省来源按词典类型填，避免每行都让上传者填一遍
            // #5（2026-10-05）：表格路径原先直接 new TermEntry，绕过了 normalize ——
            // 于是「别名里写了标准词本身」会原样入库，归一时自己命中自己（词表数据缺陷的入口）。
            ok.add(normalize(new TermEntry(standard, aliases, defaultSource(type), code)));
        }
        return ok;
    }

    /**
     * 读 Excel 全部行（取前 3 列，空单元格补 null 以保持列位）。
     *
     * `.xlsx` 走 SAX 流式（{@link ExcelStreamReader}）：内存里只留当前一行，不再整份载入 ——
     * 原先的 50MB 体积闸门只是把 OOM 阈值推后，没改变「内存 ≈ 解压后体积」这个事实。
     * 两条路径的取值口径已由 ExcelStreamReaderTest 逐行逐列比对锁住。
     *
     * `.xls`（HSSF）没有事件式 API，保留 POI 全量载入 + 体积闸门，这是有意取舍。
     */
    private List<String[]> readExcelRows(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        // 1. xlsx：流式逐个工作表事件回调
        if (fileName(file).endsWith(".xlsx")) {
            ExcelStreamReader.forEachXlsxRow(file.getInputStream(), 3, (rowNum, cells) -> {
                // 1.1 首行是表头就跳过
                if (rowNum == 0 && isHeaderRow(cells)) {
                    return;
                }
                // 1.2 三列全空的行直接丢，避免尾部空行混进失败清单
                if (cells[0] != null || cells[1] != null || cells[2] != null) {
                    rows.add(cells);
                }
            });
            return rows;
        }
        // 2. xls：全量载入（无流式 API）
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row r : sheet) {
                if (r.getRowNum() == 0 && isHeaderRow(r)) continue;
                String[] arr = new String[3];
                arr[0] = cellText(r.getCell(0));
                arr[1] = cellText(r.getCell(1));
                arr[2] = cellText(r.getCell(2));
                if (arr[0] != null || arr[1] != null || arr[2] != null) rows.add(arr);
            }
        }
        return rows;
    }

    /** 读 CSV 全部行（自动识别 UTF-8/GBK），分隔符兼容逗号与制表符 */
    private List<String[]> readCsvRows(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        // 1. 按行切分，空行与以「标准术语」开头的表头行都跳过
        for (String line : readTextAutoCharset(file).split("\r?\n")) {
            if (line.isBlank() || line.startsWith("标准术语")) continue;
            // 最多切 3 段（标准术语 / 别名 / 国标代码）；别名列内部请用、 或 ; 分隔，
            // 用半角逗号会与列分隔符冲突
            String[] parts = line.split("[,\t]", 3);
            String[] arr = new String[3];
            // 2. 补齐到三列，缺列给 null 交给上层判空
            for (int i = 0; i < 3; i++) {
                arr[i] = i < parts.length && !parts[i].isBlank() ? parts[i].trim() : null;
            }
            rows.add(arr);
        }
        return rows;
    }

    /** 规整词条：去空白、别名去重、缺省来源按类型填 */
    private TermEntry normalize(TermEntry e) {
        // 1. 标准术语去首尾空白
        String standard = e.getStandardTerm().trim();
        List<String> aliases = new ArrayList<>();
        // 2. 别名逐个去空白去重；与标准术语相同的别名丢掉（否则归一会自命中）
        if (e.getAliases() != null) {
            for (String a : e.getAliases()) {
                if (a == null || a.isBlank()) continue;
                String t = a.trim();
                if (!t.equals(standard) && !aliases.contains(t)) aliases.add(t);
            }
        }
        // 3. 来源与代码规整，代码为空给 null（区别于空串）
        String source = e.getSource() == null ? "" : e.getSource().trim();
        String code = e.getCode() == null || e.getCode().isBlank() ? null : e.getCode().trim();
        return new TermEntry(standard, aliases, source, code);
    }

    // ---------------------------------------------------------------- 索引重建

    // 原 rollback() / listBackups() / backupExists() 三个方法已随 dictionary_backups
    // 表一起废弃（批次 17）：回滚改为「基于归档版本生成提案 → 组长审核合并」，
    // 历史版本列表改为 GET /api/dictionary/archives。

    @Override
    public Map<String, Object> reindex(String type, String org) throws IOException {
        // 1. 参数展开：type 空 = 全部类型；org 空 = 当前组织，org=* = 全部组织
        List<String> types = (type == null || type.isBlank())
                ? new ArrayList<>(com.tcm.ehr.common.utils.TermTypes.ALL)
                : List.of(type);
        List<String> orgs = resolveReindexOrgs(org);

        // 2. 逐个 (type, org) 重放：单点失败不中断整批 —— 全量重放正是给「索引大面积落后」
        //    用的，一条失败就整体中止会让人反复试；失败项列进 failures 由调用方决定重试
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        long entries = 0;
        for (String t : types) {
            for (String o : orgs) {
                try {
                    Map<String, Object> one = reindexOne(t, o);
                    results.add(one);
                    Object n = one.get("entries");
                    entries += n instanceof Number num ? num.longValue() : 0L;
                } catch (Exception e) {
                    log.warn("[词典重建] ({}, {}) 失败：{}", t, o, e.getMessage());
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("type", t);
                    f.put("orgId", o);
                    f.put("error", String.valueOf(e.getMessage()));
                    failures.add(f);
                }
            }
        }

        // 3. 汇总；保留单点视图的键（type/orgId/entries/version）在只有一对时直接可见
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", types);
        out.put("orgs", orgs);
        out.put("pairs", types.size() * orgs.size());
        out.put("entries", entries);
        out.put("results", results);
        out.put("failures", failures);
        if (results.size() == 1) {
            out.putAll(results.get(0));
        }
        return out;
    }

    /** 解析目标组织：空 = 当前组织；{@code *} = 词典里出现过的全部组织 */
    private List<String> resolveReindexOrgs(String org) {
        if (org == null || org.isBlank()) {
            return List.of(RequestUtils.currentOrgId());
        }
        String trimmed = org.trim();
        if (!"*".equals(trimmed)) {
            return List.of(trimmed);
        }
        return termStore.listOrgs();
    }

    /** 重建单个 (type, org)：灌成功才 markIndexed */
    private Map<String, Object> reindexOne(String type, String orgId) throws IOException {
        List<TermEntry> entries = termStore.read(orgId, type);
        String version = termStore.contentVersion(entries);
        // 走跨实例互斥：与其它实例的导入 / 启动对账互斥
        distLock.runLocked(com.tcm.ehr.common.utils.DistLock.dictRebuildLock(type, orgId),
                () -> {
                    try {
                        esTermIndexService.rebuild(type, orgId, entries, version);
                    } catch (IOException io) {
                        throw new java.io.UncheckedIOException(io);
                    }
                    return null;
                });
        // 灌成功才记已同步：失败就让它保持落后，下次启动对账还会再试
        termStore.markIndexed(orgId, type, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("orgId", orgId);
        out.put("entries", entries.size());
        out.put("version", version);
        return out;
    }

    /** 合并同标准词的两条词条：别名取并集，其余字段以新条目为准 */
    private TermEntry mergeEntries(TermEntry oldE, TermEntry newE) {
        // 1. 别名取并集（LinkedHashSet 保序去重）
        Set<String> aliases = new LinkedHashSet<>(oldE.getAliases() == null ? List.of() : oldE.getAliases());
        if (newE.getAliases() != null) aliases.addAll(newE.getAliases());
        // 2. 来源与代码以旧条目优先：已核过的出处不该被一次导入覆盖成空
        String source = oldE.getSource() == null || oldE.getSource().isBlank() ? newE.getSource() : oldE.getSource();
        String code = oldE.getCode() == null || oldE.getCode().isBlank() ? newE.getCode() : oldE.getCode();
        // #5（2026-10-05）：合并也要剔除「标准词本身」—— 否则历史脏数据（或经合并不经过
        // normalize 的路径）会一直留在库里，归一时自己命中自己；合并是每次导入都会走的地方，
        // 在这里清等于「下次导入该词条时自动修复」。
        String std = oldE.getStandardTerm();
        List<String> cleaned = new ArrayList<>();
        for (String a : aliases) {
            if (a != null && !a.equals(std) && !cleaned.contains(a)) {
                cleaned.add(a);
            }
        }
        return new TermEntry(std, cleaned, source, code);
    }

    private String defaultSource(String type) {
        // 批次14 · 14.1：「标准来源」这张表已搬进 EntityTypes（standardRef），这里改为查目录。
        // 理由：它是纯数据，留在远处的结果是「新增词典类型要记得改两处」，而漏改**不会有编译错误**
        // —— 新类型的词条会静默地没有出处标注。搬进目录后，加类型只改 EntityTypes 一处。
        // 未登记的类型或未标出处的类型给空串（让词条由上传者自行标注），与搬走前行为一致。
        com.tcm.ehr.common.config.EntityTypes.EntityType t =
                com.tcm.ehr.common.config.EntityTypes.byKey(type);
        return t == null || t.standardRef() == null ? "" : t.standardRef();
    }

    private String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null ? "" : name.toLowerCase();
    }

    /**
     * 判断是否表头行（首行且含"标准术语"或"standardTerm"字样）。
     *
     * 流式与全量两条路径共用这一条判定 —— 表头跳过若在两边各写一份，迟早会出现
     * 「xlsx 导入多了一条表头词条、xls 没有」这类只在某种格式下复现的怪事。
     */
    private boolean isHeaderRow(String[] cells) {
        String first = cells.length > 0 ? cells[0] : null;
        return first != null && (first.contains("标准术语") || first.equalsIgnoreCase("standardTerm"));
    }

    private boolean isHeaderRow(Row r) {
        return isHeaderRow(new String[]{cellText(r.getCell(0))});
    }

    /**
     * 单元格取文本 —— **委托给全仓唯一实现** {@link ExcelCellParser#cellText(Cell)}。
     *
     * 原本这里自己写了一份，与病历导入那份在 BOOLEAN 与 FORMULA 上并不一致：
     * 同一个 .xlsx 从词典页导入和从病历页导入会读出不同文本。两份合一后，
     * 词典侧原先的两条要求（整数不带 .0、国标代码保留 3.01 这种小数）由该实现原样满足。
     */
    private String cellText(Cell cell) {
        return ExcelCellParser.cellText(cell);
    }

    /** 读文本并自动判定编码（优先 UTF-8，解出乱码则回退 GBK） */
    private String readTextAutoCharset(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        // 1. 先按 UTF-8 解；不含替换字符说明解对了
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        // 2. 出现替换字符说明是 GBK 存的，回退重解
        return new String(bytes, "GBK");
    }
}
