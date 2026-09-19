package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入摘要（批F·7.1，对应 openapi ImportSummaryVO）。
 */
@Data
public class ImportSummaryVO {

    private int total;
    private int success;
    private int failed;
    private List<Failure> failures = new ArrayList<>();

    /** 失败明细：文件名 + 原因（含行级失败） */
    @Data
    public static class Failure {
        private String filename;
        private String reason;

        public Failure() {
        }

        public Failure(String filename, String reason) {
            this.filename = filename;
            this.reason = reason;
        }
    }
}
