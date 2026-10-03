package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 词典文件**只读**：每类术语一个 JSON 文件，仅用于基础层首次播种。
 *
 * <p>批次 17 起本接口<b>只保留 read()</b>：词典真源已入库
 * （{@code dictionary_terms}），备份/回滚/版本列表改由归档版本体系承担
 * （{@code dict_archive_version} / {@code dict_archive_term}）。
 * 保留 {@code read()} 是因为基础层 3580 条术语只此一份可重建的来源。</p>
 *
 * <p>只管文件，解析与校验在 {@link IDictionaryService}。</p>
 */
public interface IDictionaryFileService {

    /** @return 词典文件所在目录 */
    Path dir();


    /**
     * 取词典文件名。
     *
     * @param type 术语类型
     * @return 文件名，如 diseases.json
     */
    String fileNameOf(String type);

    /**
     * 读取整类词典。
     *
     * @param type 术语类型
     * @return 词条列表；文件不存在视为空
     */
    List<TermEntry> read(String type) throws IOException;

    /**
     * 覆盖写入整类词典。
     *
     * @param type    术语类型
     * @param entries 词条列表
     */
    void write(String type, List<TermEntry> entries) throws IOException;


    /**
     * 当前词典版本（各类型内容合成，内容不变则稳定）。
     *
     * <p>结构化数据落库时记下这个值，用于回答"这次归一依据哪一版词典"。</p>
     *
     * @return 版本串
     */
    String currentVersion();

    /**
     * 读文本文件内容。
     *
     * @param path 文件路径
     * @return 文件文本
     */
    String readText(Path path) throws IOException;
}
