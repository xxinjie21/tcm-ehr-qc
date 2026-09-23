package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * 日志清理请求（批I·I2）：删除 {@code beforeDate}（含）之前的操作日志。
 *
 * <p>服务端会先把待删日志导出为归档 CSV 落到 {@code logs/}，成功后才执行删除。</p>
 */
@Data
public class PurgeLogDTO {

    /** 清理截止日期（yyyy-MM-dd）；该日期（不含当天）之前的日志被清理 */
    private String beforeDate;
}
