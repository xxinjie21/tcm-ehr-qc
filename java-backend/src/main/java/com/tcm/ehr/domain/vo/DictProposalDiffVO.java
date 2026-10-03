package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提案与基线的差异（批次 17）。
 *
 * <p><b>实时计算、不落表</b>：快照与基线都在库里，审核页现算即可；
 * 落一份 diff 反而要多一处会与快照不一致的数据。</p>
 */
@Data
public class DictProposalDiffVO {

    /** 新增：提案里有、基线里没有的标准词 */
    private List<Map<String, Object>> added = new ArrayList<>();

    /** 修改：两边都有，但 code / source / aliases 不同 */
    private List<Map<String, Object>> modified = new ArrayList<>();

    /** 删除：基线里有、提案里没有（合并时会被整快照替换掉） */
    private List<String> removed = new ArrayList<>();

    /** 无差异时为 true —— 这种情况提案仍可提交，但前端应提示「与基线一致」 */
    private Boolean noDiff;

    public boolean isEmptyDiff() {
        return added.isEmpty() && modified.isEmpty() && removed.isEmpty();
    }
}
