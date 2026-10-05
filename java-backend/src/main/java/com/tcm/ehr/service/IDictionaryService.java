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
     * 导入到<b>指定组织层</b>（批次 17）。
     *
     * <p>与 {@link #importDictionary(String, MultipartFile)} 的差别只在落库目标：
     * 那个固定写当前组织，这个允许管理员显式写基础层（{@code orgId = ""}）。
     * 刻意<b>不</b>开放任意 orgId 入参给普通用户 —— 提案流程才是成员改基线的唯一入口。</p>
     *
     * @param type   术语类型
     * @param file   文件（.xlsx/.xls/.csv/.json）
     * @param orgId  目标组织层；{@code ""} = 基础层
     */
    ImportResultVO importDictionary(String type, MultipartFile file, String orgId) throws IOException;

    /**
     * 读某一层当前的完整词条（不带回落，供归档快照取「合并后」的基线）。
     *
     * @param orgId 组织；{@code ""} = 基础层
     * @param type  术语类型
     */
    java.util.List<com.tcm.ehr.domain.po.TermEntry> currentTerms(String orgId, String type);

    /**
     * 只解析文件、<b>不落库</b>（供「成员上传词典文件 → 生成提案」用）。
     *
     * <p>为什么需要它：{@link #importDictionary} 会直接写基线，那是管理员特权通道；
     * 普通成员按设计<b>不能</b>直接改基线（必须走提案审核）。但成员同样需要
     * 「批量导入词典」的便利 —— 让他上传文件后<b>生成提案</b>，组长审核通过才合并，
     * 既给了便利，又不破坏审核约定。</p>
     *
     * @param type 术语类型
     * @param file Excel/CSV/JSON
     * @return 解析结果：terms=词条、failures=失败明细
     */
    ParseResult parseTerms(String type, MultipartFile file) throws IOException;

    /** 只解析不落库的结果（terms + 逐行失败明细） */
    class ParseResult {
        /** 成功解析并规整后的词条 */
        public final List<com.tcm.ehr.domain.po.TermEntry> terms;
        /** 失败明细（行号 + 原因），与导入接口同构 */
        public final List<Map<String, Object>> failures;

        public ParseResult(List<com.tcm.ehr.domain.po.TermEntry> terms,
                           List<Map<String, Object>> failures) {
            this.terms = terms;
            this.failures = failures;
        }
    }

    // 原 rollback / listBackups / backupExists 已随 dictionary_backups 表废弃（批次 17）：
    // 回滚改为「基于归档版本生成提案 → 组长审核合并」，历史版本改为 GET /api/dictionary/archives。

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
    /**
     * 手动重建 ES 索引（仅管理员；ES 是可丢弃可重放的派生索引，本入口用于「库里对、索引落后」）。
     *
     * <p>默认只重建<b>当前组织 + 指定类型</b>：基础层或别的组织若也落后，重建它们会让那一层
     * 同时进入空窗（归一 503），不该由一次手动操作顺带触发。要全量重放必须显式传参 ——
     * 那是「索引大面积丢失」时的灾难恢复入口。</p>
     *
     * @param type 术语类型；空 = 全部类型
     * @param org  组织号；空 = 当前组织；{@code *} = 词典里出现过的全部组织
     * @return 汇总：{@code pairs} / {@code entries} / {@code results} / {@code failures}；
     *         单个 (type, org) 的失败不中断整批，列进 {@code failures}
     * @throws IOException 仅当参数解析本身出错（单点失败已收进 failures）
     */
    Map<String, Object> reindex(String type, String org) throws IOException;
}
