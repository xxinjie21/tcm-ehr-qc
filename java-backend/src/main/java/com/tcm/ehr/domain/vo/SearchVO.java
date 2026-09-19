package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 多条件病历查询响应（批F·7.4，对应 openapi SearchVO）：总数 + 摘要列表。
 */
@Data
public class SearchVO {

    private long total;
    private List<Item> records = new ArrayList<>();

    @Data
    public static class Item {
        private String id;
        /** 原始文本摘要（主诉/中医诊断截取） */
        private String summary;

        public Item() {
        }

        public Item(String id, String summary) {
            this.id = id;
            this.summary = summary;
        }
    }
}
