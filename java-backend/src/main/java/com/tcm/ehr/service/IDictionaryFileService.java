package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 词典文件读写：每类术语一个 JSON 文件，导入前自动备份到 backup 子目录。
 *
 * <p>只管文件，解析与校验在 {@link IDictionaryService}。</p>
 */
public interface IDictionaryFileService {

    /** @return 词典文件所在目录 */
    Path dir();

    /** @return 备份文件所在目录 */
    Path backupDir();

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
     * 备份当前词典。
     *
     * @param type 术语类型
     * @return 备份文件名；当前无文件返回 {@code null}
     */
    String backup(String type) throws IOException;

    /**
     * 用备份覆盖当前词典。
     *
     * @param type           术语类型
     * @param backupFilename 备份文件名，类型不匹配时拒绝
     */
    void restore(String type, String backupFilename) throws IOException;

    /**
     * 列出历史版本。
     *
     * @param type 术语类型
     * @return 版本项：filename=文件名；time=导入时间；count=词条数；delta=相对当前的增减
     */
    List<Map<String, String>> listBackups(String type) throws IOException;

    /**
     * 判断备份文件是否存在。
     *
     * @param type           术语类型
     * @param backupFilename 备份文件名
     * @return 文件存在返回 true
     */
    boolean backupExists(String type, String backupFilename);

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
