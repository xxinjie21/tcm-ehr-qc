package com.tcm.ehr.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.DictionaryStore;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.ImportResultVO;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.service.IDictionaryService;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 词典服务实现：导入（Excel/CSV -> JSON -> 内存+ES）、查询、回滚、备份列表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryServiceImpl implements IDictionaryService {

    private final IDictionaryFileService fileService;
    private final DictionaryStore store;
    private final IEsTermIndexService esTermIndexService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Override
    public ImportResultVO importDictionary(String type, MultipartFile file) throws IOException {
        List<String[]> rows = parseFile(file);
        ImportResultVO vo = new ImportResultVO();
        vo.setType(type);
        vo.setTotal(rows.size());

        // 现有词典（按standardTerm索引）
        Map<String, TermEntry> merged = new LinkedHashMap<>();
        for (TermEntry e : fileService.read(type)) {
            merged.merge(e.getStandardTerm(), e, this::mergeEntries);
        }

        List<Map<String, Object>> failures = vo.getFailures();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String standard = row[0] == null ? "" : row[0].trim();
            if (standard.isEmpty()) {
                failures.add(Map.of("row", i + 1, "reason", "standardTerm为空"));
                continue;
            }
            List<String> aliases = new ArrayList<>();
            if (row[1] != null && !row[1].isBlank()) {
                for (String a : row[1].split("[、,，;；|]")) {
                    if (!a.isBlank()) aliases.add(a.trim());
                }
            }
            merged.merge(standard, new TermEntry(standard, aliases, defaultSource(type)), this::mergeEntries);
        }

        List<TermEntry> entries = new ArrayList<>(merged.values());
        String backupName = fileService.backup(type);
        fileService.write(type, entries);
        store.put(type, entries);
        esTermIndexService.rebuild(type, entries);

        vo.setImported(rows.size() - failures.size());
        vo.setFailed(failures.size());
        log.info("[词典] {} 导入完成: 文件{}行, 现有{}条, 备份{}", type, rows.size(), entries.size(),
                backupName == null ? "无(首次)" : backupName);
        return vo;
    }

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

    private TermEntry mergeEntries(TermEntry oldE, TermEntry newE) {
        Set<String> aliases = new LinkedHashSet<>(oldE.getAliases() == null ? List.of() : oldE.getAliases());
        if (newE.getAliases() != null) aliases.addAll(newE.getAliases());
        String source = oldE.getSource() == null || oldE.getSource().isBlank() ? newE.getSource() : oldE.getSource();
        return new TermEntry(oldE.getStandardTerm(), new ArrayList<>(aliases), source);
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

    /** 解析上传文件：仅支持Excel(.xlsx/.xls)与CSV（文档必做16），xlsx用POI，csv按行split（UTF-8/GBK自动尝试） */
    private List<String[]> parseFile(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        List<String[]> rows = new ArrayList<>();
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                for (Row r : sheet) {
                    if (r.getRowNum() == 0 && isHeaderRow(r)) continue;
                    String[] arr = new String[2];
                    arr[0] = cellText(r.getCell(0));
                    arr[1] = cellText(r.getCell(1));
                    if (!(arr[0] == null && arr[1] == null)) rows.add(arr);
                }
            }
        } else if (name.endsWith(".csv")) {
            String content = readTextAutoCharset(file);
            for (String line : content.split("\r?\n")) {
                if (line.isBlank() || line.startsWith("标准术语")) continue;
                int idx = line.indexOf(',');
                if (idx < 0) idx = line.indexOf('\t');
                if (idx < 0) {
                    rows.add(new String[]{line.trim(), null});
                } else {
                    rows.add(new String[]{line.substring(0, idx).trim(), line.substring(idx + 1).trim()});
                }
            }
        } else {
            throw new IllegalArgumentException("文件格式不支持：仅支持Excel(.xlsx/.xls)或CSV文件");
        }
        return rows;
    }

    private boolean isHeaderRow(Row r) {
        String first = cellText(r.getCell(0));
        return first != null && (first.contains("标准术语") || first.equalsIgnoreCase("standardTerm"));
    }

    private String cellText(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
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
