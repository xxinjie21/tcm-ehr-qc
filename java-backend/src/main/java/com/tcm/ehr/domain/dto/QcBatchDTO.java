package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * 批量质控评分重算请求：可选 filters，缺省 = 全库。
 */
@Data
public class QcBatchDTO {

    private FiltersDTO filters;

    /**
     * 幂等键（批次 5）：可空。与解析侧同一约定 ——
     * 客户端在一次用户动作里生成，重放沿用同一个值；用户再次主动点击应换新值（那是新意图）。
     *
     * <p>作用：撞 (org_id, request_key) 唯一索引时返回既有任务，不新建也不重跑。</p>
     */
    private String requestKey;
}
