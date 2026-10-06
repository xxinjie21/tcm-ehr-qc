package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.DictArchiveVersion;
import com.tcm.ehr.domain.po.TermEntry;

import java.util.List;

/**
 * 基线归档（批次 17）的接口契约：每次基线被改动后存一份合并后的快照，供历史查看与回滚。
 *
 * <p>实现见 {@code com.tcm.ehr.service.impl.DictArchiveServiceImpl}。</p>
 */
public interface IDictArchiveService {

    /** 归档一份基线快照并返回版本号 */
    Integer archive(String orgId, String type, List<TermEntry> entries, String proposalId,
                    String operator, String comment);

    /** 列出某组织某类型的归档版本（含快照已被清理的版本） */
    List<DictArchiveVersion> list(String orgId, String type);

    /** 读指定归档版本的词条快照 */
    List<TermEntry> readSnapshot(String orgId, String type, int versionNo);

    /** 查指定归档版本的元信息 */
    DictArchiveVersion findVersion(String orgId, String type, int versionNo);
}
