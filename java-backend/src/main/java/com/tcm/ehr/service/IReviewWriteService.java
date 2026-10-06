package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.vo.ReviewResultVO;

/**
 * 复核提交的写库段（批次 26.4 / 26.e 定案 A）接口契约。
 *
 * <p>归一（ES 往返）在事务外，由调用方 {@code ReviewServiceImpl#review} 完成后进入本接口；
 * 本接口只承载事务内的读改写（权限、隔离、防丢更新指纹校验、三段写库）。</p>
 *
 * <p>实现见 {@code com.tcm.ehr.service.impl.ReviewWriteServiceImpl}。</p>
 */
public interface IReviewWriteService {

    /** 事务内完成一次复核写库，返回复核结果 */
    ReviewResultVO review(String recordId, ReviewDTO dto);
}
