package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class ImportResultVO {

    private String type;
    private int total;
    private int imported;
    private int failed;
    private List<Map<String, Object>> failures = new ArrayList<>();

    /**
     * 直写导入后生成的归档版本号（批次 17）。
     *
     * <p>管理员直写同样要进归档体系 —— 否则这条通道会成为唯一一条没有历史版本的改基线方式，
     * 「出问题回不到上一版」。</p>
     */
    private Integer archiveVersion;
}
