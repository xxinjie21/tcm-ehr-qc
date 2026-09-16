package com.tcm.ehr.service;

import com.tcm.ehr.domain.vo.ImportResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 术语词典服务：导入（Excel/CSV -> JSON -> 内存+ES）、查询、回滚、备份列表
 */
public interface IDictionaryService {

    /** 术语查询：内存词典标准词/别名关键字模糊匹配 */
    List<Map<String, Object>> searchTerms(String type, String keyword) throws IOException;

    /** 词典导入：解析 -> 去重合并 -> 备份 -> 写文件 -> 刷内存/ES */
    ImportResultVO importDictionary(String type, MultipartFile file) throws IOException;

    /** 回滚：备份文件覆盖 -> 刷内存/ES */
    void rollback(String type, String backupFilename) throws IOException;

    /** 备份版本列表 */
    List<Map<String, String>> listBackups(String type) throws IOException;

    /** 备份文件是否存在 */
    boolean backupExists(String type, String backupFilename);
}
