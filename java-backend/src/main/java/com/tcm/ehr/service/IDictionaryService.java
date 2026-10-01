package com.tcm.ehr.service;

import com.tcm.ehr.domain.vo.ImportResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 术语库业务：查询、导入、回滚、版本列表。
 *
 * <p>写入路径固定为 Excel/CSV/JSON → JSON → 内存 + ES 索引。</p>
 */
public interface IDictionaryService {

    /**
     * 查询术语，供术语词典页分页浏览、输入联想、质控规则「期望值」下拉三处使用。
     *
     * <p>按标准词与别名做包含匹配；关键字为空表示不过滤。</p>
     *
     * @param type    词典类型
     * @param keyword 搜索关键字，可为空
     * @param page    页码，从 1 开始；{@code <= 0} 表示不分页、返回全部命中
     * @param size    每页条数，仅 {@code page > 0} 时生效
     * @return 视图含 {@code terms}（词条列表）与 {@code total}（命中总数）
     * @throws IOException 词典读取失败
     */
    Map<String, Object> searchTerms(String type, String keyword, int page, int size) throws IOException;

    /**
     * 导入词典文件，落库到当前组织并重建该组织的 ES 索引。
     *
     * @param type 术语类型
     * @param file Excel/CSV/JSON 文件，整文件覆盖
     * @return total/imported/failed=行数统计与失败明细
     */
    ImportResultVO importDictionary(String type, MultipartFile file) throws IOException;

    /**
     * 回滚当前组织到历史版本，并重建该组织的 ES 索引。
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

    /**
     * 强制重建当前组织某一类词典的 ES 索引（不落库、不改词条）。
     *
     * <p>用于「库里词条是对的、但索引落后」的自愈：ES 重建失败时 {@code indexed_version}
     * 不会被更新，启动对账下次会补；这个接口让它不必重启就能立刻补。</p>
     *
     * <p>只重建<b>当前组织这一层</b>。基础层或别的组织若也落后，重建它们会让那一层
     * 同时进入空窗（归一 503），不该由一次手动操作顺带触发。</p>
     *
     * @param type 术语类型
     * @return 视图含 {@code type} / {@code orgId} / {@code entries} / {@code version}
     * @throws IOException ES 重建失败
     */
    Map<String, Object> reindex(String type) throws IOException;
}
