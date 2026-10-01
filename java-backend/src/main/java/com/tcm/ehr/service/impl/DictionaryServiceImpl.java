package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.ImportResultVO;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.service.IDictionaryService;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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


    private final IDictionaryFileService fileService;
    private final IEsTermIndexService esTermIndexService;
    private final com.tcm.ehr.common.utils.DistLock distLock;
    private final com.tcm.ehr.service.DictionaryTermStore termStore;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 术语查询：读词典 JSON，按关键字对标准词与别名做包含匹配。
     *
     * <p>每次调用都直接读文件 —— 本方法只服务三处：术语词典页列表 / 输入联想 /
     * 质控规则「期望值」下拉，都是低频请求，5 类词条合计三千余条、读文件代价可忽略。</p>
     *
     * <p><b>两种返回模式</b>（由 {@code page} 决定）：</p>
     * <ul>
     *   <li>{@code page <= 0}（不分页）：返回<b>全部</b>命中词条。供质控规则下拉与
     *       输入联想使用 —— 两者都需要完整候选集才能选到任意术语。</li>
     *   <li>{@code page > 0}（分页）：只返回第 {@code page} 页（每页 {@code size} 条），
     *       供术语词典页翻页浏览。分页切片对越界做了双向夹取，页码超出范围返回空列表
     *       而非抛异常（{@code subList} 越界会抛 {@code IndexOutOfBounds}）。</li>
     * </ul>
     *
     * @param type    词典类型
     * @param keyword 搜索关键字，可为空（表示不过滤）
     * @param page    页码，从 1 开始；{@code <= 0} 表示不分页、返回全部命中
     * @param size    每页条数，仅 {@code page > 0} 时生效
     * @return 命中词条视图：{@code terms}（standardTerm / aliases）+ {@code total}（命中总数）
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
     * <p>按扩展名分流：JSON 走直传解析，Excel/CSV 走表格解析。合并以标准术语为键，
     * 同词条合并别名并保留已有的 source / code。</p>
     *
     * <p>索引重建失败会触发补偿：把文件退回导入前版本并尽力重建旧索引，之后仍抛异常 ——
     * 避免出现「文件已更新、索引未建起」导致归一结果与词典页长期不一致。</p>
     *
     * @param type 词典类型
     * @param file 上传文件（.json / .xlsx / .xls / .csv）
     * @return 导入结果（总数、成功数、失败数及逐行失败原因）
     * @throws IOException 文件解析或落盘失败；索引重建失败时也以此抛出
     * @throws IllegalArgumentException 文件格式不支持，或 JSON 结构非法
     */
    public ImportResultVO importDictionary(String type, MultipartFile file) throws IOException {
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
        String orgId = RequestUtils.currentOrgId();
        List<TermEntry> previous = termStore.read(orgId, type);
        Map<String, TermEntry> merged = new LinkedHashMap<>();
        for (TermEntry e : previous) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }
        for (TermEntry e : incoming) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }

        List<TermEntry> entries = new ArrayList<>(merged.values());
        // 3. 先备份再落库：回滚要靠这份备份
        String backupId = termStore.backup(orgId, type, RequestUtils.currentUsername());
        // 4. 落库（同一事务里更新内容版本，避免「词条换了、版本没换」而跳过重建）
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
        } catch (Exception e) {
            // DB 已改而 ES 没跟上 → 归一结果与词典页会长期不一致。退回库内容并尽力恢复索引。
            log.error("[词典] {} (org={}) ES 重建失败，回退库内容并尝试恢复索引: {}",
                    type, orgId, e.getMessage());
            compensateFailedRebuild(type, orgId, backupId, previous, contentVersion);
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
        log.info("[词典] {} 导入完成: 新解析{}条(失败{}), 合并后共{}条, 备份{}",
                type, incoming.size(), failures.size(), entries.size(),
                backupId == null ? "无(首次)" : backupId);
        return vo;
    }

    /**
     * ES 重建失败后的补偿：把库内容退回导入前的版本，再尽力把索引也建回旧版本。
     *
     * <p>两步都可能再失败 —— 那时只记日志，不掩盖最初的异常（调用方会把它抛出去）。
     * 无备份（首次导入、本组织原先没有词条）时用 {@code previous} 写回，通常是空列表。</p>
     *
     * <p>关键：补偿路径<b>不会</b>把失败的那个版本记为已同步。记了就等于谎报，
     * 启动对账会跳过重建，归一会永远停在这一版错误的数据上。</p>
     */
    private void compensateFailedRebuild(String type, String orgId, String backupId,
                                         List<TermEntry> previous, String failedVersion) {
        // 1. 先把库内容退回导入前（有备份用备份，首次导入按原内容写回）
        try {
            if (backupId != null) {
                List<TermEntry> snap = termStore.readBackup(backupId);
                termStore.replace(orgId, type, snap == null ? List.of() : snap);
            } else {
                termStore.replace(orgId, type, previous);
            }
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
            ok.add(new TermEntry(standard, aliases, defaultSource(type), code));
        }
        return ok;
    }

    /** 读 Excel 全部行（POI），空单元格补空串以保持列位 */
    private List<String[]> readExcelRows(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            // 1. 只读第一个工作表，逐行取前三列
            for (Row r : sheet) {
                // 2. 首行是表头就跳过
                if (r.getRowNum() == 0 && isHeaderRow(r)) continue;
                String[] arr = new String[3];
                arr[0] = cellText(r.getCell(0));
                arr[1] = cellText(r.getCell(1));
                arr[2] = cellText(r.getCell(2));
                // 3. 三列全空的行直接丢，避免尾部空行混进失败清单
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

    // ---------------------------------------------------------------- 回滚 / 备份

    @Override
    /**
     * 回滚：用备份文件覆盖当前词典，并以恢复后的内容全量重建 ES 索引。
     *
     * @param type 词典类型
     * @param backupFilename 备份文件名
     * @throws IOException 文件恢复或索引重建失败
     * @throws IllegalArgumentException 备份文件不存在或与词典类型不匹配
     */
    public void rollback(String type, String backupFilename) throws IOException {
        // 0. 组织校验放在最前：拿别的组织的备份来覆盖本组织词典是越权，不能靠后续失败兜住
        String orgId = RequestUtils.currentOrgId();
        if (!termStore.backupMatches(backupFilename, orgId, type)) {
            throw new IllegalArgumentException("备份不存在，或不属于当前组织 / 不匹配该词典类型");
        }
        // 1. 用备份快照覆盖本组织词条
        List<TermEntry> entries = termStore.readBackup(backupFilename);
        if (entries == null) {
            throw new IllegalArgumentException("备份不存在或已损坏，无法回滚");
        }
        String contentVersion = termStore.replace(orgId, type, entries);
        // 2. 再按恢复后的内容重建索引；库与索引必须同版本，否则归一会拿到旧数据
        esTermIndexService.rebuild(type, orgId, entries, contentVersion);
        termStore.markIndexed(orgId, type, contentVersion);
        log.info("[词典] {} (org={}) 回滚到 {}，现有{}条", type, orgId, backupFilename, entries.size());
    }

    @Override
    /**
     * 备份版本列表，直接委托文件服务，按时间倒序。
     *
     * @param type 词典类型
     * @return 备份条目列表
     * @throws IOException 遍历备份目录或读取当前词典失败
     */
    public List<Map<String, String>> listBackups(String type) throws IOException {
        String orgId = RequestUtils.currentOrgId();
        List<Map<String, String>> out = new ArrayList<>();
        for (com.tcm.ehr.domain.po.DictionaryBackup b : termStore.listBackups(orgId, type)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("filename", b.getId());
            m.put("time", b.getCreateTime() == null ? "" : b.getCreateTime().toString());
            m.put("count", String.valueOf(countOf(b.getSnapshot())));
            out.add(m);
        }
        return out;
    }

    /** 快照里的词条数；解析不了就报 0，不为展示一个数字而抛异常 */
    private int countOf(String snapshot) {
        List<com.tcm.ehr.domain.po.TermEntry> list = termStore.readBackupBySnapshot(snapshot);
        return list == null ? 0 : list.size();
    }

    @Override
    /**
     * 备份文件是否存在，直接委托文件服务。
     *
     * @param type 词典类型
     * @param backupFilename 备份文件名
     * @return 文件名合法且文件存在时为 {@code true}
     */
    public boolean backupExists(String type, String backupFilename) {
        // 同样要校验组织：否则能探测到别的组织的备份是否存在
        return termStore.backupMatches(backupFilename, RequestUtils.currentOrgId(), type);
    }

    // ---------------------------------------------------------------- 内部工具

    @Override
    public Map<String, Object> reindex(String type) throws IOException {
        // 只重建「当前组织这一层」：基础层与其它组织不在此动，避免让它们进入重建空窗
        String orgId = RequestUtils.currentOrgId();
        List<TermEntry> entries = termStore.read(orgId, type);
        String version = termStore.contentVersion(entries);
        // 同样走跨实例互斥：与其它实例的导入 / 启动对账互斥
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
        return new TermEntry(oldE.getStandardTerm(), new ArrayList<>(aliases), source, code);
    }

    private String defaultSource(String type) {
        // 按词典类型给出权威出处；未登记的类型给空串（让词条由上传者自行标注）
        return switch (type) {
            case "disease" -> "中医临床诊疗术语 疾病";
            case "pattern" -> "中医病证分类与代码 GB/T 15657-2021";
            case "symptom" -> "中医临床诊疗术语 症状";
            case "herb" -> "中国药典2025年版";
            case "formula" -> "中医方剂大辞典";
            default -> "";
        };
    }

    private String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null ? "" : name.toLowerCase();
    }

    /** 判断是否表头行（首行且含"标准术语"或"别名"字样） */
    private boolean isHeaderRow(Row r) {
        String first = cellText(r.getCell(0));
        return first != null && (first.contains("标准术语") || first.equalsIgnoreCase("standardTerm"));
    }

    private String cellText(Cell cell) {
        // 1. 空单元格给 null
        if (cell == null) return null;
        CellType type = cell.getCellType();
        // 2. 按单元格类型取值
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            // 整数按 long 输出（避免 1.0 这种尾数）；非整数保留小数（国标代码常形如 3.01）
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) && !Double.isInfinite(d)
                        ? String.valueOf((long) d)
                        : String.valueOf(d);
            }
            case FORMULA -> cell.toString().trim();
            default -> null;
        };
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
