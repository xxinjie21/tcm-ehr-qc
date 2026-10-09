package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.DictionaryVersion;
import com.tcm.ehr.domain.po.TermEntry;

import java.util.List;

/**
 * 组织级词典存储的接口契约：词条落 {@code dictionary_terms}，版本对账落
 * {@code dictionary_versions}，导入前存档落 {@code dictionary_backups}。
 *
 * <p><b>组织维度语义</b>：{@code orgId} 为空串 {@code ""} = 基础层（全组织共享）。
 * 某组织没有自有词条时读到基础层，而不是读到空。</p>
 *
 * <p>实现见 {@code com.tcm.ehr.service.impl.DictionaryTermStoreImpl}。</p>
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
     * <p>口径是<b>叠加</b>而非回退：基础层与本组织都有词条时取并集，
     * 同一标准词以本组织为准；只有一侧有词条时就是那一侧。</p>
     */
    List<TermEntry> readEffective(String orgId, String type);

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
}
