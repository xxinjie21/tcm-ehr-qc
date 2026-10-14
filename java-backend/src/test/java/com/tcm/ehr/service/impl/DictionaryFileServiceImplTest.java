package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 词典文件服务：默认从 <b>classpath</b> 读取（随 jar 发布，服务器无需另放文件）。
 *
 * <p>回归点：词典曾用绝对磁盘路径配置（{@code D:/.../data/dictionaries}），部署到服务器即失效。
 * 现在打包在 classpath:{@code dictionaries/}，此测试证明资源确实随产物可读。</p>
 */
class DictionaryFileServiceImplTest {

    @Test
    @DisplayName("从 classpath 读取打包的词典文件（非空）")
    void readsBundledDictionaryFromClasspath() throws Exception {
        DictionaryFileServiceImpl svc = new DictionaryFileServiceImpl(new ObjectMapper());
        ReflectionTestUtils.setField(svc, "dictDir", "classpath:dictionaries");

        List<TermEntry> diseases = svc.read("disease");

        assertNotNull(diseases);
        assertFalse(diseases.isEmpty(), "diseases.json 应打包在 classpath:dictionaries 并可读");
    }

    @Test
    @DisplayName("文件不存在时返回空列表（不抛异常）")
    void missingFileReturnsEmpty() throws Exception {
        DictionaryFileServiceImpl svc = new DictionaryFileServiceImpl(new ObjectMapper());
        ReflectionTestUtils.setField(svc, "dictDir", "classpath:no-such-dir");

        List<TermEntry> none = svc.read("disease");

        assertNotNull(none);
        assertFalse(!none.isEmpty(), "缺失的词典文件应视为空，而不是报错");
    }
}
