package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 复核结果（出参，批D·5.1，对应 openapi ReviewResultVO）。
 */
@Data
public class ReviewResultVO {

    /** 已完成 / 待复核 */
    private String status;
    private int score;
    private List<ErrorItem> errors = new ArrayList<>();

    @Data
    public static class ErrorItem {
        private String type;
        private String msg;

        public ErrorItem() {
        }

        public ErrorItem(String type, String msg) {
            this.type = type;
            this.msg = msg;
        }
    }
}
