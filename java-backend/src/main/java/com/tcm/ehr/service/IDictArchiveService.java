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

    /**
     * 归档一份基线快照，<b>失败不抛</b>，把原因收敛成警告返回。
     *
     * <p>调用方（如管理员直写导入）在调用本方法时基线<b>已经写入并提交</b>。
     * 归档只是随后的记账动作：失败若抛出去会把整个请求变成 500，让用户以为导入没成，
     * 实际已生效（重试还会再合并一次）。故由服务层兜住，原因写进响应体、栈入日志。</p>
     *
     * @return 成功时 {@code versionNo} 非空、{@code warning} 为空；失败反之
     */
    ArchiveOutcome archiveQuietly(String orgId, String type, List<TermEntry> entries,
                                  String proposalId, String operator, String comment);

    /** {@link #archiveQuietly} 的结果：版本号与失败原因二者必有一个为空 */
    record ArchiveOutcome(Integer versionNo, String warning) {
        public static ArchiveOutcome ok(Integer versionNo) {
            return new ArchiveOutcome(versionNo, null);
        }

        public static ArchiveOutcome failed(String warning) {
            return new ArchiveOutcome(null, warning);
        }
    }
}
