package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.DictionaryStore;
import com.tcm.ehr.common.utils.LlmClient;
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
    private final DictionaryStore store;
    private final IEsTermIndexService esTermIndexService;
    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;

    /** 词典 PDF 智能转换子开关；依赖 llm.enabled=true */
    @Value("${nlp.convert-enabled:false}")
    private boolean convertEnabled;

    @Override
    public List<Map<String, Object>> searchTerms(String type, String keyword) throws IOException {
        List<TermEntry> entries = store.get(type);
        if (entries.isEmpty()) {
            entries = fileService.read(type);
            store.put(type, entries);
        }
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
    public ImportResultVO importDictionary(String type, MultipartFile file) throws IOException {
        List<Map<String, Object>> failures = new ArrayList<>();
        List<TermEntry> incoming = fileName(file).endsWith(".json")
                ? parseJsonEntries(file, failures)
                : parseTabularEntries(type, file, failures);

        // 合并：现有词典打底，新条目并入（同 standardTerm 合并别名，保留已有 source/code）
        Map<String, TermEntry> merged = new LinkedHashMap<>();
        for (TermEntry e : fileService.read(type)) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }
        for (TermEntry e : incoming) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }

        List<TermEntry> entries = new ArrayList<>(merged.values());
        String backupName = fileService.backup(type);
        fileService.write(type, entries);
        store.put(type, entries);
        esTermIndexService.rebuild(type, entries);

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
    public ConvertPreviewVO convertFromPdf(String type, MultipartFile file) throws IOException {
        ConvertPreviewVO vo = new ConvertPreviewVO();
        vo.setType(type);

        // 双开关：llm.enabled 总控 + nlp.convert-enabled 子控。关闭属「预期内不可用」，
        // 用 IllegalArgumentException 让 GlobalExceptionHandler 回 400 + 明确文案（而非 500 系统异常）
        if (!llmClient.isEnabled() || !convertEnabled) {
            throw new IllegalArgumentException(
                    "PDF 智能转换未启用（需同时开启 llm.enabled 与 nlp.convert-enabled）。"
                            + "可改用 JSON 直传，或用离线脚本 tools/convert-standard-pdf.py 转换后导入。");
        }
        if (!llmClient.isAvailable()) {
            throw new IllegalArgumentException(
                    "LLM 通道不可用（provider=" + llmClient.provider() + "），无法智能转换。"
                            + "请检查 llm 配置，或改用 JSON 直传 / 离线脚本。");
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
    public void rollback(String type, String backupFilename) throws IOException {
        fileService.restore(type, backupFilename);
        List<TermEntry> entries = fileService.read(type);
        store.put(type, entries);
        esTermIndexService.rebuild(type, entries);
        log.info("[词典] {} 回滚到 {}，现有{}条", type, backupFilename, entries.size());
    }

    @Override
    public List<Map<String, String>> listBackups(String type) throws IOException {
        return fileService.listBackups(type);
    }

    @Override
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
