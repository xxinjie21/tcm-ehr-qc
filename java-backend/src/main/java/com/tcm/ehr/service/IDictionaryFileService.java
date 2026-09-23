package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 词典文件服务：data/dictionaries/{diseases,patterns,symptoms,herbs,formulas}.json
 * 导入时自动备份到 data/dictionaries/backup/，回滚从备份恢复
 */
public interface IDictionaryFileService {

    Path dir();

    Path backupDir();

    /** 词典类型 -> JSON文件名 */
    String fileNameOf(String type);

    /** 读取某类词典 */
    List<TermEntry> read(String type) throws IOException;

    /** 写入某类词典 */
    void write(String type, List<TermEntry> entries) throws IOException;

    /** 导入前备份当前词典，返回备份文件名；当前无文件则返回 null */
    String backup(String type) throws IOException;

    /** 回滚：备份文件覆盖当前词典文件 */
    void restore(String type, String backupFilename) throws IOException;

    /**
     * 备份版本列表（按时间倒序）。每项含：filename / time（可读时间）/
     * count（该备份词条数）/ delta（相对当前词典的增减，形如 +3 / -2 / 0）。
     */
    List<Map<String, String>> listBackups(String type) throws IOException;

    /** 备份文件是否存在 */
    boolean backupExists(String type, String backupFilename);

    /**
     * 当前词典版本（5 类词典内容合成，内容不变则稳定、变更即变）。
     * 用于结构化数据落库时记录「本次归一依据哪一版词典」。
     */
    String currentVersion();

    /** 读取文本文件内容 */
    String readText(Path path) throws IOException;
}
