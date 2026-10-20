package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.CleanDTO;
import com.tcm.ehr.domain.vo.CleanTaskVO;

import java.util.List;

/**
 * 数据清洗任务编排（异步）：提交 / 查进度 / 取消 / 列表。
 *
 * <p>清洗由同步接口改异步任务后，任务生命周期（排队、进度、取消、终态）
 * 单独成服务，与清洗业务逻辑（{@link IGovernanceService#clean}）解耦。</p>
 */
public interface ICleanBatchService {

    /** 提交清洗任务（异步入队，立即返回任务视图） */
    CleanTaskVO submit(CleanDTO dto);

    /** 查任务进度 */
    CleanTaskVO status(String id);

    /** 最近任务列表（按创建时间倒序） */
    List<CleanTaskVO> list();

    /** 请求取消（只发信号，终态由执行方落） */
    CleanTaskVO cancel(String id);
}
