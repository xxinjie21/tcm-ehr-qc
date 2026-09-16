package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.util.List;

/**
 * ES术语索引服务：term_{type} 索引构建
 * standard_term(keyword精确) + aliases(text ik_max_word，别名全量入库) + source(keyword)
 */
public interface IEsTermIndexService {

    /** 索引名：term_{type} */
    String indexName(String type);

    /** 索引是否存在 */
    boolean exists(String type) throws IOException;

    /** 全量重建：删除旧索引 -> 建索引 -> bulk灌入 */
    void rebuild(String type, List<TermEntry> entries) throws IOException;
}
