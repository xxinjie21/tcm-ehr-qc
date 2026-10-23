package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.DictionaryVersion;
import com.tcm.ehr.domain.po.TermEntry;

import java.util.List;
import java.util.Map;

/**
 * 组织级词典存储的接口契约：词条落 dictionary_terms，版本对账落
 * dictionary_versions，导入前存档落 dictionary_backups。
 *
 * 组织维度语义：orgId 为空串 {@code ""} = 基础层（全组织共享）。
 * 某组织没有自有词条时读到基础层，而不是读到空。
 *
 * 实现见 com.tcm.ehr.service.impl.DictionaryTermStoreImpl。
 */
public interface IDictionaryTermStore {

    /** 基础层组织号：空串，不用 null（null 在唯一索引里互不相等，挡不住重复） */
    String BASE_ORG = "";

    // ---- 词典作用域（查询 / 导出接口的 scope 取值）----
    //
    // 三档对应用户看到的三个概念，取值名与前端滑动按钮一一对应：
    //   effective = 基础层 ∪ 本组织（归一与输入联想实际使用的口径）
    //   base      = 仅基础层（即「系统默认词典」，由 data/dictionaries/*.json 播种）
    //   org       = 仅本组织自有词条（「组内词典」，叠加在基础层之上的增量层）
    //
    // 默认一律取 effective：既有的输入联想、质控规则下拉都按「实际生效」取候选，
    // 换成 base/org 会让它们选到归一里根本不生效的词。

    /** 生效词典：基础层 ∪ 本组织（组织层同标准词覆盖基础层） */
    String SCOPE_EFFECTIVE = "effective";

    /** 系统默认词典：仅基础层 */
    String SCOPE_BASE = "base";

    /** 组内词典：仅本组织自有词条（不含基础层） */
    String SCOPE_ORG = "org";

    /**
     * 读某组织某类型的有效词条。
     *
     * 口径是叠加而非回退：基础层与本组织都有词条时取并集，
     * 同一标准词以本组织为准；只有一侧有词条时就是那一侧。
     */
    List<TermEntry> readEffective(String orgId, String type);

    /**
     * 一次读回某组织「基础层 ∪ 本组织」生效的全部类型词条，按 type 分组。
     *
     * 口径与逐类调用 {@link #readEffective} 逐位一致（同一标准词以本组织为准）。存在的意义是
     * 消除 N+1：报告/看板要 8 类 × (基础层 + 本组织) = 最多 16 次读，每次都带一次版本查询 +
     * 一次词条查询（≈32 条 SQL）。这里用一条 {@code org_id IN ('', ?)} 取回两层、在内存里合并，
     * 整趟只发 1 条 SQL。
     *
     * @param orgId 组织号；空串/null 视为基础层
     * @return type → 生效词条（无词条的类型不出现在返回里）
     */
    Map<String, List<TermEntry>> readEffectiveAll(String orgId);

    /** 读某组织某类型的自有词条（不回落到基础层） */
    List<TermEntry> read(String orgId, String type);

    /** 整快照替换某组织某类型的词条，返回新内容版本号 */
    String replace(String orgId, String type, List<TermEntry> entries);

    /** 标记某组织某类型已灌入 ES 的版本 */
    void markIndexed(String orgId, String type, String indexedVersion);

    /** 查某组织某类型的版本对账记录 */
    DictionaryVersion findVersion(String orgId, String type);

    /** 列出有词条的组织号 */
    List<String> listOrgs();

    /** 某组织某类型的 DB 版本是否已与 ES 对账一致 */
    boolean isSynced(String orgId, String type);

    /** 计算一组词条的内容指纹（忽略顺序） */
    String contentVersion(List<TermEntry> entries);

    /** 某组织所有类型的有效词典合并指纹 */
    String effectiveDictVersion(String orgId);

    /** 某组织所有类型的有效词条总数 */
    int effectiveTermCount(String orgId);

    /**
     * 术语建议召回用的字符倒排索引，连同它所索引的那份生效词条表。
     *
     * 为什么两者必须一起给：postings 里的值指向 entries 的串编号，
     * 分两次取（先取词条、再取索引）在词典刚好变更时会让两者对不上。一次取回就同源。
     *
     * 用途是把全表扫描缩成候选扫描：召回门槛是「与原文重叠 ≥2 个字符」，
     * 而重叠只可能发生在「出现在原文里的字符」上 —— 所以「出现在原文任一字符的倒排表里」
     * 是入选的必要条件，据此取出的候选集是超集，结果集与全表扫描逐条一致。
     *
     * @param entries     生效词条（基础层 ∪ 本组织，口径同 {@link #readEffective}）
     * @param stringOwner 串编号 → 该串所属词条在 entries 中的下标
     * @param postings    字符 → 含该字符的串编号（升序；同一字符在一个串里出现多次只登记一次）
     */
    record RecallIndex(List<TermEntry> entries, int[] stringOwner, Map<Character, int[]> postings) {
    }

    /**
     * 取某组织某类型的召回索引。
     *
     * 随词典版本失效 —— 与词条缓存同一版本号，不新增失效通道。
     *
     * @param orgId 组织号；空串/null 视为基础层
     * @param type  术语类型
     */
    RecallIndex recallIndex(String orgId, String type);

    /**
     * 取某组织某类型、指定作用域的召回索引。
     *
     * 作用域与词典页的三档一一对应（{@link #SCOPE_BASE} / {@link #SCOPE_ORG} / 其余一律
     * {@link #SCOPE_EFFECTIVE}），口径与 {@link #read} / {@link #readEffective} 完全一致。
     *
     * 调用方必须拿 {@code entries()} 当词条真源，不要再单独 read 一次：
     * 索引里的串编号指向的就是 entries 的下标，分两次取在词典刚好变更时会对不上。
     *
     * 与 {@link #recallIndex(String, String)} 共用同一个 VersionedCache 与同一套版本号
     * （本组织哈希 + 基础层哈希），只是缓存键多一档作用域 —— 不新增失效通道。
     *
     * @param scope base = 仅基础层；org = 仅本组织；其余 = 基础层 ∪ 本组织
     */
    RecallIndex recallIndex(String orgId, String type, String scope);
}
