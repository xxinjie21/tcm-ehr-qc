package com.tcm.ehr.service;

import com.tcm.ehr.domain.vo.ConvertPreviewVO;
import com.tcm.ehr.domain.vo.ImportResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 术语词典服务：导入（Excel/CSV/JSON -> JSON -> 内存+ES）、PDF 智能转换预览、查询、回滚、备份列表
 */
public interface IDictionaryService {

    /** 术语查询：读词典 JSON 文件，标准词/别名关键字模糊匹配 */
    List<Map<String, Object>> searchTerms(String type, String keyword) throws IOException;

    /** 词典导入：解析 -> 去重合并 -> 备份 -> 写文件 -> 刷内存/ES */
    ImportResultVO importDictionary(String type, MultipartFile file) throws IOException;

    /**
     * PDF 智能转换：PDFBox 抽文本 -> LLM 提取候选 -> 返回预览。
     * <b>不落库</b>，管理员确认后再走 {@link #importDictionary}。
     * 开关关闭或 LLM 不可用时抛 {@link IllegalArgumentException}（回 400 + 明确文案）。
     */
    ConvertPreviewVO convertFromPdf(String type, MultipartFile file) throws IOException;

    /** 回滚：备份文件覆盖 -> 刷内存/ES */
    void rollback(String type, String backupFilename) throws IOException;

    /** 备份版本列表 */
    List<Map<String, String>> listBackups(String type) throws IOException;

    /** 备份文件是否存在 */
    boolean backupExists(String type, String backupFilename);
}
