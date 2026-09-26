package com.tcm.ehr.service;

import com.tcm.ehr.domain.vo.ConvertPreviewVO;
import com.tcm.ehr.domain.vo.ImportResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 术语库业务：查询、导入、PDF 转换预览、回滚、版本列表。
 *
 * <p>写入路径固定为 Excel/CSV/JSON → JSON → 内存 + ES 索引。</p>
 */
public interface IDictionaryService {

    /**
     * 查询术语，供页面展示与输入联想。
     *
     * @param type    术语类型
     * @param keyword 关键字，为空表示不过滤
     * @return 命中的术语列表
     */
    List<Map<String, Object>> searchTerms(String type, String keyword) throws IOException;

    /**
     * 导入词典文件并刷新内存与 ES 索引。
     *
     * @param type 术语类型
     * @param file Excel/CSV/JSON 文件，整文件覆盖
     * @return total/imported/failed=行数统计与失败明细
     */
    ImportResultVO importDictionary(String type, MultipartFile file) throws IOException;

    /**
     * 把国标 PDF 转为候选术语预览。
     *
     * <p>只预览不落库，确认后走 {@link #importDictionary} 入库；LLM 不可用时抛
     * IllegalArgumentException，由上层转 400 与可读提示。</p>
     *
     * @param type 术语类型
     * @param file PDF 文件
     * @return candidates=候选术语；failed=抽取失败明细
     */
    ConvertPreviewVO convertFromPdf(String type, MultipartFile file) throws IOException;

    /**
     * 回滚到历史版本并刷新内存与 ES 索引。
     *
     * @param type           术语类型
     * @param backupFilename 备份文件名
     */
    void rollback(String type, String backupFilename) throws IOException;

    /**
     * 列出历史版本。
     *
     * @param type 术语类型
     * @return 版本项：filename / time / count / delta
     */
    List<Map<String, String>> listBackups(String type) throws IOException;

    /**
     * 判断备份文件是否存在。
     *
     * @param type           术语类型
     * @param backupFilename 备份文件名
     * @return 存在返回 true
     */
    boolean backupExists(String type, String backupFilename);
}
