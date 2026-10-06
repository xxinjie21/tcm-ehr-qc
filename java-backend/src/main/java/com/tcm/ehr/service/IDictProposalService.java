package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.DictProposalDTOs;
import com.tcm.ehr.domain.po.DictProposal;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.DictProposalDiffVO;
import com.tcm.ehr.domain.vo.DictProposalVO;

import java.util.List;

/**
 * 词典基线更新提案（批次 17）的接口契约：导出基线、查看差异、编辑、审批、回滚。
 *
 * <p>实现见 {@code com.tcm.ehr.service.impl.DictProposalServiceImpl}。</p>
 */
public interface IDictProposalService {

    /** 导出某组织某类型的当前有效词典作为提案基线 */
    List<TermEntry> exportBaseline(String orgId, String type);

    /** 提交一份「完整目标词典」提案 */
    DictProposalVO submit(String orgId, String type, List<TermEntry> terms, String submitUserId);

    /** 提案列表：成员只看自己提交的，组长看本组 + 基础层 */
    List<DictProposalVO> list(DictProposalDTOs.ProposalQuery query);

    /** 按 id 取提案 */
    DictProposal get(String proposalId);

    /** 提案快照与当前基线的差异 */
    DictProposalDiffVO diff(String proposalId);

    /** 组长在提案快照上直接增删改 */
    void editTerms(String proposalId, List<TermEntry> terms, String operator);

    /** 审批：通过则合并进基线并归档，驳回则只更新状态 */
    DictProposalVO audit(String proposalId, boolean approve, String comment, String auditor);

    /** 回滚到指定归档版本（以管理员身份生成一份新提案并直接通过） */
    DictProposalVO rollbackTo(String orgId, String type, int versionNo, String operator);

    /** 读提案快照词条 */
    List<TermEntry> readSnapshot(String proposalId);

    /** 提案快照词条数 */
    int snapshotCount(String proposalId);
}
