package com.tcm.ehr.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 词典 PDF 智能转换预览结果（批A·1.4）。
 *
 * <p>流程：上传 PDF → PDFBox 抽文本 → LLM 按 Prompt 提取 → 本 VO 返回候选预览 →
 * 管理员确认后再走 {@code POST /api/dictionary/import} 入库。
 * <b>预览阶段不落任何数据</b>。</p>
 */
@Data
public class ConvertPreviewVO {

    /** 目标词典类型（disease/pattern/symptom/herb/formula），由请求指定 */
    private String type;

    /** 转换成功、待确认入库的候选条目 */
    private List<Candidate> candidates = new ArrayList<>();

    /** 转换失败的片段及原因（原文保留，便于人工补录或下载排查） */
    private List<Failed> failed = new ArrayList<>();

    /** 候选条目（字段与 import 的 TermEntry 对齐，code 为可选国标代码） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        private String standardTerm;
        private List<String> aliases = new ArrayList<>();
        private String source;
        private String code;
    }

    /** 失败片段 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failed {
        private String text;
        private String reason;
    }
}
