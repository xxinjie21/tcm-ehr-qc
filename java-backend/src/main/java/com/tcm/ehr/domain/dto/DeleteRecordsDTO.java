package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量删除病历请求。
 */
@Data
public class DeleteRecordsDTO {

    private List<String> ids;
}
