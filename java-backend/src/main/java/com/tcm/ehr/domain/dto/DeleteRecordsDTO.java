package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量删除病历请求。
 *
 * @param ids 待删除病历 ID 列表；空列表由 Bean Validation 直接拦成 400（批次 26.5）。
 */
public record DeleteRecordsDTO(
        @NotEmpty(message = "未选择要操作的病历") List<String> ids) {
}
