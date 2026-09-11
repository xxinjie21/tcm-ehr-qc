package com.tcm.ehr.dictionary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.List;
import java.util.Map;

/**
 * 词典文件服务：data/dictionaries/{diseases,patterns,symptoms,herbs,formulas}.json
 * 导入时自动备份到 data/dictionaries/backup/，回滚从备份恢复
 */
@Slf4j
@Service
public class DictionaryFileService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Value("${dictionary.dir:data/dictionaries}")
    private String dictDir;

    public Path dir() {
        return Paths.get(dictDir);
    }

    public Path backupDir() {
        return dir().resolve("backup");
    }

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

    public List<TermEntry> read(String type) throws IOException {
        Path file = dir().resolve(fileNameOf(type));
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        return mapper.readValue(file.toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, TermEntry.class));
    }

    public void write(String type, List<TermEntry> entries) throws IOException {
        Files.createDirectories(dir());
        Path file = dir().resolve(fileNameOf(type));
        mapper.writeValue(file.toFile(), entries);
    }

    /** 导入前备份当前词典，返回备份文件名；当前无文件则返回null */
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

    /** 回滚：备份文件覆盖当前词典文件 */
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

    public List<Map<String, String>> listBackups(String type) throws IOException {
        List<Map<String, String>> result = new ArrayList<>();
        Path backupDir = backupDir();
        if (!Files.exists(backupDir)) {
            return result;
        }
        String prefix = fileNameOf(type) + ".bak_";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, prefix + "*")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String ts = name.substring(prefix.length());
                LocalDateTime t = LocalDateTime.parse(ts, TS);
                result.add(Map.of(
                        "filename", name,
                        "time", t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                ));
            }
        }
        result.sort((a, b) -> b.get("time").compareTo(a.get("time")));
        return result;
    }

    public boolean backupExists(String type, String backupFilename) {
        return Files.exists(backupDir().resolve(backupFilename));
    }

    public String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
