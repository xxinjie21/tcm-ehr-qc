package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.NlpBatchDTO;
import com.tcm.ehr.domain.vo.NlpTaskVO;

import java.util.List;

/**
 * 批量解析任务：提交、查询、取消、列表。
 *
 * <p>任务在服务端队列中执行，提交后不占用请求线程。</p>
 */
public interface INlpBatchService {

    /**
     * 按范围提交批量解析任务。
     *
     * @param dto       filters=范围条件；limit=处理条数上限，0 表示不限
     * @param createdBy 提交人
     * @return 任务ID、状态与计划条数
     */
    NlpTaskVO submit(NlpBatchDTO dto, String createdBy);

    /**
     * 按病历ID集合提交（导入后自动解析用）。
     *
     * @param ids       病历ID集合，为空返回 {@code null}
     * @param createdBy 提交人
     * @return 任务视图；未开启抽取时抛 IllegalArgumentException
     */
    NlpTaskVO submitIds(List<String> ids, String createdBy);

    /**
     * 查询任务。
     *
     * @param id 任务ID
     * @return 任务状态与进度；不存在返回 {@code null}
     */
    NlpTaskVO get(String id);

    /**
     * 取消任务。
     *
     * @param id 任务ID
     * @return 取消后的任务状态；不存在抛 IllegalArgumentException
     */
    NlpTaskVO cancel(String id);

    /**
     * 最近任务列表。
     *
     * @return 按提交时间倒序
     */
    List<NlpTaskVO> list();
}
