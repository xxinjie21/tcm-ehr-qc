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

    /** 读某组织某类型的有效词条（无自有词条时回落基础层） */
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
