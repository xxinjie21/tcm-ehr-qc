package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入摘要。
 */
@Data
public class ImportSummaryVO {

    private int total;
    private int success;
    private int failed;
    private List<Failure> failures = new ArrayList<>();

    /** 失败明细：文件名 + 原因（含行级失败）+ 该行原始数据（供前端查看与「仅重传失败行」） */
    @Data
    public static class Failure {
        private String filename;
        private String reason;
        /**
         * 该行的原始字段值（字段名 → 文本）；文件级失败（空文件/超限等）为 null。
         *
         * <p>承载「失败的是哪一条、内容是什么」，也是「仅重传失败行」改写后回传的载荷。</p>
         */
        private Map<String, String> row;

        public Failure() {
        }

        public Failure(String filename, String reason) {
            this.filename = filename;
            this.reason = reason;
        }

        public Failure(String filename, String reason, Map<String, String> row) {
            this.filename = filename;
            this.reason = reason;
            this.row = row == null ? null : new LinkedHashMap<>(row);
        }
    }
}
