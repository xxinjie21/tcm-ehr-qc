package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 多条件病历查询请求（批F·7.4，对应 openapi SearchDTO）。
 */
@Data
public class SearchDTO {

    private String registrationNo;
    private String outpatientNo;
    private String gender;
    private String department;
    private String doctorId;
    /** [开始日期, 结束日期]（yyyy-MM-dd） */
    private List<String> dateRange;
    private String pattern;
    /** 分级：合格/待复核/无效 */
    private String grade;
    private String status;
    private Integer page;
    private Integer pageSize;
}
