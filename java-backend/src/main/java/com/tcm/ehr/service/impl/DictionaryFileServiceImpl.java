package com.tcm.ehr.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IDictionaryFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 词典文件**只读**服务：每类术语一个 JSON。
 *
 * <p>默认打包在 <b>classpath:{@code dictionaries/}</b>（随 jar 一起发布，服务器上无需另放文件）；
 * 需要外部目录时用 {@code dictionary.dir} 覆盖（可填 {@code classpath:xxx} 或文件系统路径）。
 * 词典真源已在 {@code dictionary_terms}，本文件仅用于基础层首次播种。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryFileServiceImpl implements IDictionaryFileService {

    private final ObjectMapper mapper;

    /** 词典目录：默认 classpath:dictionaries（打包进 jar）；可用外部目录覆盖 */
    @Value("${dictionary.dir:classpath:dictionaries}")
    private String dictDir;

    /**
     * 词典类型 → JSON 文件名（文件名由 EntityTypes 统一登记）。
     *
     * @throws IllegalArgumentException 类型未登记时抛出
     */
    public String fileNameOf(String type) {
        String name = com.tcm.ehr.common.config.EntityTypes.fileNameOf(type);
        if (name == null) {
            throw new IllegalArgumentException("未知词典类型: " + type);
        }
        return name;
    }

    /**
     * 读取某类词典的全部词条。
     *
     * <p>文件不存在视为「该类词典为空」并返回空列表，而非报错 —— 首次导入时目录尚无文件。</p>
     *
     * @throws IOException 文件存在但读取或 JSON 解析失败
     */
    public List<TermEntry> read(String type) throws IOException {
        Resource res = resource(type);
        if (!res.exists()) {
            return new ArrayList<>();
        }
        try (InputStream in = res.getInputStream()) {
            return mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, TermEntry.class));
        }
    }

    /** 解析词典文件资源：{@code classpath:dictionaries/<name>} 或文件系统 {@code <dir>/<name>} */
    private Resource resource(String type) {
        String name = fileNameOf(type);
        if (dictDir.startsWith("classpath:")) {
            return new ClassPathResource(dictDir.substring("classpath:".length()) + "/" + name);
        }
        return new FileSystemResource(Paths.get(dictDir, name));
    }
}
