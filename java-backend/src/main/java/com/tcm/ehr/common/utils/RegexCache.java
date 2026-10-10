package com.tcm.ehr.common.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 正则表达式编译结果的<b>有界备忘</b>（P2-1）。
 *
 * <p><b>解决什么</b>：格式规则的正则来自配置（{@code QcRuleSet.FormatRule.expr}），
 * 原先在 {@code formatOk} 里对<b>每条病历、每条规则</b>现编译一次 —— 4 万条病历 × 2 条格式规则
 * 就是 8 万次 {@code Pattern.compile}。编译一个正则要解析表达式并构造 NFA，是毫秒级里最贵的
 * 那类常量操作，而表达式在整个运行期是常量。</p>
 *
 * <p><b>为什么是进程内静态备忘而不是「挂版本号的缓存」</b>：这里的映射是<b>纯函数</b>
 * （同一个表达式永远编译出等价的 Pattern），不存在「配置改了缓存还是旧的」这种失效问题 ——
 * 与本项目「配置类一律以 DB 为权威 + 版本号失效」那条约束不冲突：那条约束管的是
 * <b>配置内容</b>的权威来源，而这里只备忘「把这段文本编译成对象」的结果，内容仍来自 DB。</p>
 *
 * <p><b>为什么有上界</b>：表达式由管理员按组织配置，理论上可无限新增。
 * 到界后不再写入（退化为每次都编译），而不是淘汰已有项 —— 淘汰会让常用表达式反复重编译，
 * 反而制造抖动。上界取 {@value #MAX_ENTRIES}，远超「一个部署里出现过的不同格式规则数」。</p>
 *
 * <p><b>非法表达式</b>：{@link Pattern#compile(String)} 会抛，本类<b>不吞</b>它 ——
 * 调用方（{@code formatOk}）本来就要把「规则自身写错」与「病历不合规」分开处理
 * （前者放行不扣分），异常必须留给它看见。</p>
 */
public final class RegexCache {

    /** 备忘条数上界：到界后只读不写，退化为每次现编译 */
    private static final int MAX_ENTRIES = 512;

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private RegexCache() {
    }

    /**
     * 取（必要时编译）一个正则。
     *
     * @param expr 表达式；调用方需保证非 null 非空白（本类不做空判断，传 null 会按 {@code Pattern.compile} 的语义抛 NPE）
     * @return 编译结果（可能是备忘里复用的实例）
     * @throws java.util.regex.PatternSyntaxException 表达式非法
     */
    public static Pattern of(String expr) {
        // 1. 命中即复用
        Pattern hit = CACHE.get(expr);
        if (hit != null) {
            return hit;
        }
        // 2. 未命中：先编译（可能抛），成功了再决定要不要入表
        Pattern compiled = Pattern.compile(expr);
        if (CACHE.size() < MAX_ENTRIES) {
            CACHE.put(expr, compiled);
        }
        return compiled;
    }
}
