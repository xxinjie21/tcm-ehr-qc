package com.tcm.ehr.service;

import com.tcm.ehr.domain.vo.StandardizationReportVO;

/**
 * 标准化质量报告服务（批次 24）。
 *
 *
 * 只读：不改库、不重建索引、不触发重算。报告里的每个数字都来自现有数据，
 *
 * 一次请求内算完即返回。
 */
public interface IStandardizationReportService {

    /**
     * 生成标准化质量报告。
     *
     *
     * 【权限：登录即可】但受组织数据域约束：乙类（病历相关）只统计当前可见的病历；
     *
     * 甲类（词典质量）看的是本组织 + 基础层的词表，与病历无关。
     *
     * @return 甲类标准符合度 + 乙类数据集覆盖度
     */
    StandardizationReportVO report();
}