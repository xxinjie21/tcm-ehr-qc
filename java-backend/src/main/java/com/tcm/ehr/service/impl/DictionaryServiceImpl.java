package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.LlmClient;
import com.tcm.ehr.common.utils.TermTypes;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.ConvertPreviewVO;
import com.tcm.ehr.domain.vo.ImportResultVO;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.service.IDictionaryService;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * 词典服务实现：导入（Excel/CSV/JSON -> JSON -> 内存+ES）、PDF 智能转换预览、查询、回滚、备份列表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryServiceImpl implements IDictionaryService {

    /** PDF 送 LLM 的文本上限（字符）：超长截断，避免超出模型上下文 */
    private static final int MAX_TEXT_CHARS = 20000;

    private final IDictionaryFileService fileService;
    private final IEsTermIndexService esTermIndexService;
    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;

    /**
     * 词典 PDF 智能转换子开关；依赖 {@code llm.enabled}。
     *
     * <p>2026-09-22 从 {@code nlp.convert-enabled} 挪到 {@code llm.convert-enabled}：真正干活的是
     * {@link LlmClient}（Spring AI），与 :8001 的 python-nlp 服务无关。</p>
     */
    @Value("${llm.convert-enabled:false}")
    private boolean convertEnabled;

    @Override
    /**
     * 术语查询：读词典 JSON，按关键字对标准词与别名做包含匹配。
     *
     * <p>关键字为空表示不过滤；每次调用都直接读文件 —— 词典体量小，且本方法只服务
     * 术语词典页的列表 / 搜索。</p>
     *
     * @param type 词典类型
     * @param keyword 搜索关键字，可为空
     * @return 命中词条（standardTerm / aliases），最多 100 条
     * @throws IOException 词典文件读取失败
     */
    public List<Map<String, Object>> searchTerms(String type, String keyword) throws IOException {
        // 词典列表直接读 JSON 文件（原先借 DictionaryStore 当文件缓存）。
        // DictionaryStore 已随「归一不再内存兜底」删除；这里每次读一次文件 ——
        // 全词典仅 131 条、文件十几 KB，且本方法只服务术语词典页的列表/搜索，代价可忽略。
        List<TermEntry> entries = fileService.read(type);
        List<Map<String, Object>> result = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim();
        // 1. 逐条比对标准术语与别名，任一命中即算命中
        for (TermEntry e : entries) {
            boolean hit = kw.isEmpty()
                    || e.getStandardTerm().contains(kw)
                    || (e.getAliases() != null && e.getAliases().stream().anyMatch(a -> a.contains(kw)));
            if (hit) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("standardTerm", e.getStandardTerm());
                m.put("aliases", e.getAliases());
                result.add(m);
                // 2. 满 100 条就停：词典页一次只渲染前 100 条，全量返回没有意义
                if (result.size() >= 100) break;
            }
        }
        return result;
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
        // 1. 按扩展名分流解析：JSON 直传解析，Excel/CSV 走表格解析
        List<TermEntry> incoming = fileName(file).endsWith(".json")
                ? parseJsonEntries(file, failures)
                : parseTabularEntries(type, file, failures);

        // 2. 合并：现有词典打底，新条目并入（同 standardTerm 合并别名，保留已有 source/code）
        List<TermEntry> previous = fileService.read(type);
        Map<String, TermEntry> merged = new LinkedHashMap<>();
        for (TermEntry e : previous) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }
        for (TermEntry e : incoming) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }

        List<TermEntry> entries = new ArrayList<>(merged.values());
        // 3. 先备份再覆盖写文件：回滚要靠这份备份
        String backupName = fileService.backup(type);
        fileService.write(type, entries);
        // 4. 覆盖成功后重建索引；失败走补偿回滚
        try {
            esTermIndexService.rebuild(type, entries, fileService.currentVersion());
        } catch (Exception e) {
            // 文件已经覆盖而索引没建起来（rebuild 先 delete 再 create，失败时索引可能根本不存在）
            // → 归一结果与词典页会长期不一致。回滚文件并尽力恢复索引，回到「同版本」状态。
            log.error("[词典] {} ES 重建失败，回滚文件并尝试恢复索引: {}", type, e.getMessage());
            compensateFailedRebuild(type, backupName, previous);
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
                backupName == null ? "无(首次)" : backupName);
        return vo;
    }

    /**
     * ES 重建失败后的补偿：把文件退回导入前的版本，再尽力把索引也建回旧版本。
     *
     * <p>两步都可能再失败 —— 那时只记日志，不掩盖最初的异常（调用方会把它抛出去）。
     * 无备份（首次导入、原先没有文件）时用 {@code previous} 写回，通常是空列表。</p>
     */
    private void compensateFailedRebuild(String type, String backupName, List<TermEntry> previous) {
        // 1. 先把文件退回导入前的版本（首次导入无备份时按原内容写回）
        try {
            if (backupName != null) {
                fileService.restore(type, backupName);
            } else {
                fileService.write(type, previous);
            }
        } catch (Exception e) {
            // 文件都退不回去就没必要再试索引，记日志交人工重新导入
            log.error("[词典] {} 文件回滚失败，文件与索引可能不一致，需重新导入修复: {}", type, e.getMessage());
            return;
        }
        // 2. 再尽力把索引建回旧版本；失败只记日志，不掩盖最初的异常
        try {
            esTermIndexService.rebuild(type, previous, fileService.currentVersion());
            log.warn("[词典] {} 已回滚到导入前的词典并重建索引", type);
        } catch (Exception e) {
            log.error("[词典] {} 索引恢复失败（该类术语的归一不可用，请重新导入）: {}", type, e.getMessage());
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

    // ---------------------------------------------------------------- PDF 智能转换

    @Override
    /**
     * PDF 智能转换预览：PDFBox 抽取文本 -> 送 LLM 提取术语候选，不落库。
     *
     * <p>需 llm.enabled 与 llm.convert-enabled 双开关同时开启且 LLM 可用；文本超
     * {@value #MAX_TEXT_CHARS} 字时截断并在 failed 中提示，扫描件（无文本层）直接拒绝。
     * 管理员确认候选后再走 {@link #importDictionary} 落盘。</p>
     *
     * @param type 词典类型
     * @param file 上传的 PDF 文件
     * @return 转换预览（候选词条 + 失败原因）
     * @throws IOException 文件读取或 PDF 解析失败
     * @throws IllegalArgumentException 功能未启用、LLM 不可用、非 PDF、无文本或未提取到术语
     */
    public ConvertPreviewVO convertFromPdf(String type, MultipartFile file) throws IOException {
        ConvertPreviewVO vo = new ConvertPreviewVO();
        vo.setType(type);

        // 双开关：llm.enabled 总控 + llm.convert-enabled 子控（子控默认已开），两个都在 llm 段下。
        // 关闭属「预期内不可用」，用 IllegalArgumentException 让 GlobalExceptionHandler 回 400 +
        // 明确文案（而非 500 系统异常）。
        // 文案一律说人话、不出现配置项名：用户看到 llm.enabled / llm.convert-enabled 只会
        // 以为是配置文件的问题，而这两个开关在页面上本来就打不开，说了也解决不了。
        // 1. 开关未开 → 预期内不可用，400 + 明确替代方案
        if (!llmClient.isEnabled() || !convertEnabled) {
            throw new IllegalArgumentException(
                    "PDF 智能转换未启用 —— 需要先开启 AI 能力：请让系统管理员在「导入 LLM」里"
                            + "填写通道与密钥并保存，之后即可直接上传 PDF。"
                            + "也可以改用 JSON 直传，或用离线脚本 tools/convert-standard-pdf.py 转换后导入。");
        }
        // 2. 开关开了但服务连不上 → 同样 400，文案指向「联系管理员」而非配置项名
        if (!llmClient.isAvailable()) {
            throw new IllegalArgumentException(
                    "AI 服务当前连不上，无法智能转换。请让系统管理员确认 AI 服务已启动、"
                            + "配置填写正确后重试；也可以改用 JSON 直传 / 离线脚本。");
        }

        // 3. 抽文本并前置校验：扫描件（无文本层）直接拒绝，不浪费一次模型调用
        String text = extractPdfText(file);
        if (text.isBlank()) {
            throw new IllegalArgumentException("PDF 未抽取到文本（可能是扫描件），请改用离线脚本或 JSON 直传");
        }
        // 4. 超长截断并把这件事写进 failed，让用户知道只转了前半部分
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
            vo.getFailed().add(new ConvertPreviewVO.Failed("（PDF 超出 " + MAX_TEXT_CHARS + " 字，已截断）",
                    "文本过长，仅转换前 " + MAX_TEXT_CHARS + " 字；建议用离线脚本分批处理"));
        }

        // 5. 送模型抽术语；返回空按失败处理，不当成功
        String raw = llmClient.chat(LlmClient.DICT_CONVERT_SYSTEM_PROMPT, text);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 调用失败（已降级），请稍后重试或改用 JSON 直传 / 离线脚本");
        }
        // 6. 解析候选；一条都没提到且没有失败项，说明模型没给出可用内容
        parseCandidates(raw, vo);
        if (vo.getCandidates().isEmpty() && vo.getFailed().isEmpty()) {
            throw new IllegalArgumentException("LLM 未从 PDF 中提取到术语条目，请确认 PDF 内容或改用离线脚本");
        }
        log.info("[词典] {} PDF 转换完成: 候选{}条, 失败{}条", type, vo.getCandidates().size(), vo.getFailed().size());
        return vo;
    }

    /** 抽 PDF 全文（PDFBox）；无文本层时返回空串，由上层转成失败明细 */
    private String extractPdfText(MultipartFile file) throws IOException {
        // 1. 只收 PDF，其余格式走导入通道
        if (!fileName(file).endsWith(".pdf")) {
            throw new IllegalArgumentException("智能转换仅支持 .pdf；Excel/CSV/JSON 请直接走导入");
        }
        // 2. 按坐标排序抽文本，多栏排版才不会串行
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    /** 从模型返回里解析候选词条；解析失败的行进失败明细，不影响其余候选 */
    private void parseCandidates(String raw, ConvertPreviewVO vo) {
        String json = stripCodeFence(raw);
        List<ConvertPreviewVO.Candidate> list;
        // 1. 整体按数组解析；解析不了就把原文记进失败明细，不静默丢弃
        try {
            list = objectMapper.readValue(json, new TypeReference<List<ConvertPreviewVO.Candidate>>() {
            });
        } catch (JacksonException e) {
            // 模型没按格式返回：把原文放进 failed，便于人工查看而不是静默丢弃
            vo.getFailed().add(new ConvertPreviewVO.Failed(truncate(raw, 500), "LLM 返回不是合法 JSON 数组"));
            return;
        }
        // 2. 逐个校验标准术语，坏的记失败、好的收进候选
        int idx = 0;
        for (ConvertPreviewVO.Candidate c : list) {
            idx++;
            if (c == null || c.getStandardTerm() == null || c.getStandardTerm().isBlank()) {
                vo.getFailed().add(new ConvertPreviewVO.Failed("第 " + idx + " 个元素",
                        "标准术语列为空"));
                continue;
            }
            c.setStandardTerm(c.getStandardTerm().trim());
            vo.getCandidates().add(c);
        }
    }

    /** 模型常把 JSON 包在 markdown 代码块里，解析前剥掉 */
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

    /** 截断超长文本，避免单个字段把行撑爆 */
    private String truncate(String s, int max) {
        // 1. 超长才截断并补省略号，短文本原样返回
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
        // 1. 先用备份覆盖词典文件
        fileService.restore(type, backupFilename);
        // 2. 再按恢复后的内容重建索引；文件与索引必须同版本，否则归一会拿到旧数据
        List<TermEntry> entries = fileService.read(type);
        esTermIndexService.rebuild(type, entries, fileService.currentVersion());
        log.info("[词典] {} 回滚到 {}，现有{}条", type, backupFilename, entries.size());
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
        return fileService.listBackups(type);
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
        return fileService.backupExists(type, backupFilename);
    }

    // ---------------------------------------------------------------- 内部工具

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
