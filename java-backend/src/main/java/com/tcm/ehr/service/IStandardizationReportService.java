package com.tcm.ehr.service;

import com.tcm.ehr.domain.vo.StandardizationReportVO;

/**
 * 标准化质量报告服务（批次 21）。
 *
 * <p>只读：不改库、不重建索引、不触发重算。报告里的每个数字都来自现有数据，
 * 一次请求内算完即返回。</p>
 */
public interface IStandardizationReportService {

    /**
     * 生成标准化质量报告（不限时间）。
     *
     * <p>【权限：登录即可】但受组织数据域约束：乙类（病历相关）只统计当前可见的病历；
     * 甲类（词典质量）看的是本组织 + 基础层的词表，与病历无关。</p>
     *
     * @return 甲类标准符合度 + 乙类数据集覆盖度
     */
    StandardizationReportVO report();

    /**
     * 按接诊时间区间生成报告。
     *
     * <p>为什么要区间：病历接诊时间跨度大（实测 2019-01 ~ 2025-12），
     * 混在一起看不出「换了词表之后有没有变好」—— 新词表只对重跑过解析的病历生效，
     * 而不同批次解析的病历接诊时间往往不同。</p>
     *
     * @param start 起（yyyy-MM-dd），为空表示不限
     * @param end   止（yyyy-MM-dd），为空表示不限。只给一端按「未给」处理
     * @return 同上，并附带本次区间与按月分组
     */
    StandardizationReportVO report(String start, String end);

    /**
     * 把报告导出为 CSV（批次 28 · 28.23）。
     *
     * <p>与 {@link #report(String, String)} 同一数据源、同一数据域，走同一套
     * 「登录即可 + 组织数据域」约束；之所以放在后端生成，是为了和另外两条导出
     * （数据集导出 {@code POST /api/export/dataset}、日志导出 {@code GET /api/logs/export}）
     * 对齐 —— 前端只负责收文件流落盘。</p>
     *
     * @param start 起（yyyy-MM-dd），为空表示不限
     * @param end   止（yyyy-MM-dd），为空表示不限
     * @return 带 UTF-8 BOM 的 CSV 字节；列固定为 区块 / 指标 / 数值 / 说明
     */
    byte[] reportCsv(String start, String end);
}