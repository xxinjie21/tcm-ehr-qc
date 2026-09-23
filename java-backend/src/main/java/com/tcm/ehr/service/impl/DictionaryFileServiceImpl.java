package com.tcm.ehr.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IDictionaryFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词典文件服务实现：data/dictionaries/*.json 读写、备份、回滚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryFileServiceImpl implements IDictionaryFileService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper mapper;

    @Value("${dictionary.dir:data/dictionaries}")
    private String dictDir;

    /** currentVersion 缓存：内容戳不变则复用，避免批任务逐条重算 */
    private volatile String cachedVersion;
    private volatile long cachedStamp = -1;

    @Override
    public Path dir() {
        return Paths.get(dictDir);
    }

    @Override
    public Path backupDir() {
        return dir().resolve("backup");
    }

    @Override
    public String fileNameOf(String type) {
        return switch (type) {
            case "disease" -> "diseases.json";
            case "pattern" -> "patterns.json";
            case "symptom" -> "symptoms.json";
            case "herb" -> "herbs.json";
            case "formula" -> "formulas.json";
            default -> throw new IllegalArgumentException("未知词典类型: " + type);
        };
    }

    @Override
    public List<TermEntry> read(String type) throws IOException {
        Path file = dir().resolve(fileNameOf(type));
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        return mapper.readValue(file.toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, TermEntry.class));
    }

    @Override
    public void write(String type, List<TermEntry> entries) throws IOException {
        Files.createDirectories(dir());
        Path file = dir().resolve(fileNameOf(type));
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
    }

    @Override
    public String backup(String type) throws IOException {
        Path file = dir().resolve(fileNameOf(type));
        if (!Files.exists(file)) {
            return null;
        }
        Files.createDirectories(backupDir());
        String backupName = fileNameOf(type) + ".bak_" + LocalDateTime.now().format(TS);
        Files.copy(file, backupDir().resolve(backupName), StandardCopyOption.REPLACE_EXISTING);
        return backupName;
    }

    @Override
    public void restore(String type, String backupFilename) throws IOException {
        Path src = backupDir().resolve(backupFilename);
        if (!Files.exists(src)) {
            throw new IllegalArgumentException("备份文件不存在: " + backupFilename);
        }
        if (!backupFilename.startsWith(fileNameOf(type) + ".bak_")) {
            throw new IllegalArgumentException("备份文件与词典类型不匹配");
        }
        Files.createDirectories(dir());
        Files.copy(src, dir().resolve(fileNameOf(type)), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public List<Map<String, String>> listBackups(String type) throws IOException {
        List<Map<String, String>> result = new ArrayList<>();
        Path backupDir = backupDir();
        if (!Files.exists(backupDir)) {
            return result;
        }
        int currentCount = read(type).size();
        String prefix = fileNameOf(type) + ".bak_";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, prefix + "*")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String ts = name.substring(prefix.length());
                LocalDateTime t = LocalDateTime.parse(ts, TS);
                int count = countOf(p);
                int delta = count - currentCount;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("filename", name);
                row.put("time", t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                row.put("count", String.valueOf(count));
                row.put("delta", (delta > 0 ? "+" : "") + delta);
                result.add(row);
            }
        }
        result.sort((a, b) -> b.get("time").compareTo(a.get("time")));
        return result;
    }

    /** 备份文件词条数；解析失败按 0 计（不因单个坏文件拖垮整个列表） */
    private int countOf(Path path) {
        try {
            List<TermEntry> entries = mapper.readValue(path.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, TermEntry.class));
            return entries.size();
        } catch (Exception e) {
            log.warn("[词典] 备份文件解析失败，词条数按 0 计: {} ({})", path.getFileName(), e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean backupExists(String type, String backupFilename) {
        return Files.exists(backupDir().resolve(backupFilename));
    }

    @Override
    public String currentVersion() {
        try {
            StringBuilder sb = new StringBuilder();
            long stamp = 0;
            for (String type : List.of("disease", "pattern", "symptom", "herb", "formula")) {
                Path f = dir().resolve(fileNameOf(type));
                if (Files.exists(f)) {
                    stamp = stamp * 31 + Files.getLastModifiedTime(f).toMillis() + Files.size(f);
                    sb.append(type).append('=').append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
                } else {
                    sb.append(type).append("=\n");
                }
            }
            if (cachedVersion != null && stamp == cachedStamp) {
                return cachedVersion;
            }
            String v = RecordUtil.md5Hex(sb.toString()).substring(0, 12);
            cachedVersion = v;
            cachedStamp = stamp;
            return v;
        } catch (Exception e) {
            log.warn("[词典] 版本计算失败: {}", e.getMessage());
            return "unknown";
        }
    }

    @Override
    public String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
