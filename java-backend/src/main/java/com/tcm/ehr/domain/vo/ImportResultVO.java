package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 词典导入的执行结果。
 *
 * 导入与归档是两个先后步骤：词条进库、索引重建属于导入，写归档版本属于随后的记账动作。
 *
 * ⚠️ archiveWarning 非空表示导入已生效、仅归档失败。此时不可让调用方重试导入，
 * 否则会重复写入词条；正确处置是按告警提示补归档。
 */
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
     * 管理员直写同样要进归档体系 —— 否则这条通道会成为唯一一条没有历史版本的改基线方式，
     * 「出问题回不到上一版」。
     */
    private Integer archiveVersion;

    /**
     * 归档失败时的告警（null = 归档正常）。
     *
     * 为什么要有这个字段：导入本身已提交（词条进库、索引重建），归档只是随后的
     * 记账动作。它失败时若把整个请求变成 500，用户会以为「导入没成」而重试，
     * 而实际已经生效；静默丢弃又会让归档体系与词典内容对不上（5 份限额与回滚历史错位）。
     * 所以：照常返回 200，把原因明确写在这里。
     */
    private String archiveWarning;
}
