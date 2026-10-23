package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.util.List;

/**
 * 词典文件**只读**：每类术语一个 JSON（默认打包在 classpath:dictionaries）。
 *
 * 词典真源已入库（dictionary_terms），本接口只保留 {@code read()} 用于基础层首次播种，
 * 以及 {@code fileNameOf()} 的文件名映射。备份/回滚/版本由归档版本体系承担。
 */
public interface IDictionaryFileService {

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
}
