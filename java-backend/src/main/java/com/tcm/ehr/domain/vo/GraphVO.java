package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质控检验图谱（出参，批D·3.3；ECharts 2D 力导向数据）。
 *
 * <p>节点 = 病历（size=1）+ 实体（9 类，size=频次）；边 = 病历→实体关联 / 证候→治法·方剂规则 /
 * 冲突边（红色虚线）。节点上限 200、边上限 800，超限 {@code truncated=true}。</p>
 */
@Data
public class GraphVO {

    private List<Node> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();
    private boolean truncated;
    private String hint;
    /** 各类节点计数（图例/统计） */
    private Map<String, Integer> counts = new LinkedHashMap<>();

    @Data
    public static class Node {
        private String id;
        private String name;
        /** record / disease / pattern / symptom / tongue / pulse / formula / herb / cause / treatment */
        private String type;
        /** 病历=1；实体=频次 */
        private int size;
    }

    @Data
    public static class Edge {
        private String source;
        private String target;
        /** rel / rule / conflict */
        private String type;
        /** 冲突原因（type=conflict 时） */
        private String label;
    }
}
