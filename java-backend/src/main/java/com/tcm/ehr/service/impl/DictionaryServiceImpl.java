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
     * @param type    词典类型
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
        for (TermEntry e : entries) {
            boolean hit = kw.isEmpty()
                    || e.getStandardTerm().contains(kw)
                    || (e.getAliases() != null && e.getAliases().stream().anyMatch(a -> a.contains(kw)));
            if (hit) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("standardTerm", e.getStandardTerm());
                m.put("aliases", e.getAliases());
                result.add(m);
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
     * @throws IOException              文件解析或落盘失败；索引重建失败时也以此抛出
     * @throws IllegalArgumentException 文件格式不支持，或 JSON 结构非法
     */
    public ImportResultVO importDictionary(String type, MultipartFile file) throws IOException {
        List<Map<String, Object>> failures = new ArrayList<>();
        List<TermEntry> incoming = fileName(file).endsWith(".json")
                ? parseJsonEntries(file, failures)
                : parseTabularEntries(type, file, failures);

        // 合并：现有词典打底，新条目并入（同 standardTerm 合并别名，保留已有 source/code）
        List<TermEntry> previous = fileService.read(type);
        Map<String, TermEntry> merged = new LinkedHashMap<>();
        for (TermEntry e : previous) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }
        for (TermEntry e : incoming) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }

        List<TermEntry> entries = new ArrayList<>(merged.values());
        String backupName = fileService.backup(type);
        fileService.write(type, entries);
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
        try {
            if (backupName != null) {
                fileService.restore(type, backupName);
            } else {
                fileService.write(type, previous);
            }
        } catch (Exception e) {
            log.error("[词典] {} 文件回滚失败，文件与索引可能不一致，需重新导入修复: {}", type, e.getMessage());
            return;
        }
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
        try {
            parsed = objectMapper.readValue(readTextAutoCharset(file), new TypeReference<List<TermEntry>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "JSON 解析失败：需为 TermEntry 数组，形如 [{\"standardTerm\":\"消渴\",\"aliases\":[\"消渴病\"],\"source\":\"...\",\"code\":\"...\"}]");
        }
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
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            rows = readExcelRows(file);
        } else if (name.endsWith(".csv")) {
            rows = readCsvRows(file);
        } else {
            throw new IllegalArgumentException("文件格式不支持：仅支持 Excel(.xlsx/.xls)、CSV 或 JSON");
        }

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
            ok.add(new TermEntry(standard, aliases, defaultSource(type), code));
        }
        return ok;
    }

    private List<String[]> readExcelRows(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
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

    private List<String[]> readCsvRows(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : readTextAutoCharset(file).split("\r?\n")) {
            if (line.isBlank() || line.startsWith("标准术语")) continue;
            // 最多切 3 段（标准术语 / 别名 / 国标代码）；别名列内部请用 、 或 ; 分隔，
            // 用半角逗号会与列分隔符冲突
            String[] parts = line.split("[,\t]", 3);
            String[] arr = new String[3];
            for (int i = 0; i < 3; i++) {
                arr[i] = i < parts.length && !parts[i].isBlank() ? parts[i].trim() : null;
            }
            rows.add(arr);
        }
        return rows;
    }

    private TermEntry normalize(TermEntry e) {
        String standard = e.getStandardTerm().trim();
        List<String> aliases = new ArrayList<>();
        if (e.getAliases() != null) {
            for (String a : e.getAliases()) {
                if (a == null || a.isBlank()) continue;
                String t = a.trim();
                if (!t.equals(standard) && !aliases.contains(t)) aliases.add(t);
            }
        }
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
     * @throws IOException              文件读取或 PDF 解析失败
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
        if (!llmClient.isEnabled() || !convertEnabled) {
            throw new IllegalArgumentException(
                    "PDF 智能转换未启用 —— 需要先开启 AI 能力：请让系统管理员在「导入 LLM」里"
                            + "填写通道与密钥并保存，之后即可直接上传 PDF。"
                            + "也可以改用 JSON 直传，或用离线脚本 tools/convert-standard-pdf.py 转换后导入。");
        }
        if (!llmClient.isAvailable()) {
            throw new IllegalArgumentException(
                    "AI 服务当前连不上，无法智能转换。请让系统管理员确认 AI 服务已启动、"
                            + "配置填写正确后重试；也可以改用 JSON 直传 / 离线脚本。");
        }

        String text = extractPdfText(file);
        if (text.isBlank()) {
            throw new IllegalArgumentException("PDF 未抽取到文本（可能是扫描件），请改用离线脚本或 JSON 直传");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
            vo.getFailed().add(new ConvertPreviewVO.Failed("（PDF 超出 " + MAX_TEXT_CHARS + " 字，已截断）",
                    "文本过长，仅转换前 " + MAX_TEXT_CHARS + " 字；建议用离线脚本分批处理"));
        }

        String raw = llmClient.chat(LlmClient.DICT_CONVERT_SYSTEM_PROMPT, text);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 调用失败（已降级），请稍后重试或改用 JSON 直传 / 离线脚本");
        }
        parseCandidates(raw, vo);
        if (vo.getCandidates().isEmpty() && vo.getFailed().isEmpty()) {
            throw new IllegalArgumentException("LLM 未从 PDF 中提取到术语条目，请确认 PDF 内容或改用离线脚本");
        }
        log.info("[词典] {} PDF 转换完成: 候选{}条, 失败{}条", type, vo.getCandidates().size(), vo.getFailed().size());
        return vo;
    }

    private String extractPdfText(MultipartFile file) throws IOException {
        if (!fileName(file).endsWith(".pdf")) {
            throw new IllegalArgumentException("智能转换仅支持 .pdf；Excel/CSV/JSON 请直接走导入");
        }
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private void parseCandidates(String raw, ConvertPreviewVO vo) {
        String json = stripCodeFence(raw);
        List<ConvertPreviewVO.Candidate> list;
        try {
            list = objectMapper.readValue(json, new TypeReference<List<ConvertPreviewVO.Candidate>>() {
            });
        } catch (JacksonException e) {
            // 模型没按格式返回：把原文放进 failed，便于人工查看而不是静默丢弃
            vo.getFailed().add(new ConvertPreviewVO.Failed(truncate(raw, 500), "LLM 返回不是合法 JSON 数组"));
            return;
        }
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
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ---------------------------------------------------------------- 回滚 / 备份

    @Override
    /**
     * 回滚：用备份文件覆盖当前词典，并以恢复后的内容全量重建 ES 索引。
     *
     * @param type           词典类型
     * @param backupFilename 备份文件名
     * @throws IOException              文件恢复或索引重建失败
     * @throws IllegalArgumentException 备份文件不存在或与词典类型不匹配
     */
    public void rollback(String type, String backupFilename) throws IOException {
        fileService.restore(type, backupFilename);
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
     * @param type           词典类型
     * @param backupFilename 备份文件名
     * @return 文件名合法且文件存在时为 {@code true}
     */
    public boolean backupExists(String type, String backupFilename) {
        return fileService.backupExists(type, backupFilename);
    }

    // ---------------------------------------------------------------- 内部工具

    private TermEntry mergeEntries(TermEntry oldE, TermEntry newE) {
        Set<String> aliases = new LinkedHashSet<>(oldE.getAliases() == null ? List.of() : oldE.getAliases());
        if (newE.getAliases() != null) aliases.addAll(newE.getAliases());
        String source = oldE.getSource() == null || oldE.getSource().isBlank() ? newE.getSource() : oldE.getSource();
        String code = oldE.getCode() == null || oldE.getCode().isBlank() ? newE.getCode() : oldE.getCode();
        return new TermEntry(oldE.getStandardTerm(), new ArrayList<>(aliases), source, code);
    }

    private String defaultSource(String type) {
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

    private boolean isHeaderRow(Row r) {
        String first = cellText(r.getCell(0));
        return first != null && (first.contains("标准术语") || first.equalsIgnoreCase("standardTerm"));
    }

    private String cellText(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
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

    private String readTextAutoCharset(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        return new String(bytes, "GBK");
    }
}
