package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 事前质控检查结果。
 */
@Data
public class QcCheckVO {

    private List<String> missingFields = new ArrayList<>();
    private List<FormatError> formatErrors = new ArrayList<>();
    private boolean isDuplicate;

    @Data
    public static class FormatError {
        private String field;
        private String msg;

        public FormatError() {
        }

        public FormatError(String field, String msg) {
            this.field = field;
            this.msg = msg;
        }
    }
}
