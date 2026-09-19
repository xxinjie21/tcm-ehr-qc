package com.tcm.ehr.service.impl;

import tools.jackson.databind.ObjectMapper;
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

    @Override
    public boolean backupExists(String type, String backupFilename) {
        return Files.exists(backupDir().resolve(backupFilename));
    }

    @Override
    public String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
