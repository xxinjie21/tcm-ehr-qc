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
    /**
     * 本次聚合范围内<b>被规则表覆盖到</b>的证候名（可能为空）。
     *
     * <p>用来把「判过、确实没冲突」与「根本没规则可判」区分开：{@code LogicChecker.RULES}
     * 只覆盖少数证候，未覆盖的证候按设计<b>不判冲突</b>（不误杀）。若这个列表为空，
     * 则 {@code edges} 里必然没有 rule / conflict 边，此时对用户说「证候与治法一致」是
     * 把「没判」说成了「没问题」——前端据此换文案。</p>
     *
     * <p>只下发「命中到的证候名」，不下发整张规则表，避免规则口径在两处各写一份。</p>
     */
    private List<String> coveredPatterns = new ArrayList<>();

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
