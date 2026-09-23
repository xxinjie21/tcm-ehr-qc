package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.NlpBatchDTO;
import com.tcm.ehr.domain.vo.NlpTaskVO;

import java.util.List;

/**
 * NLP 批量解析任务服务（批K·K-a）：提交 / 查询 / 取消 / 列表。
 */
public interface INlpBatchService {

    /** 提交批量解析任务（仅管理员提交；返回含计划条数的任务视图） */
    NlpTaskVO submit(NlpBatchDTO dto, String createdBy);

    /** 查询任务进度（不存在返回 null） */
    NlpTaskVO get(String id);

    /** 取消任务（QUEUED 直接置 CANCELLED；RUNNING 置取消位） */
    NlpTaskVO cancel(String id);

    /** 最近任务列表（按创建时间倒序） */
    List<NlpTaskVO> list();
}
