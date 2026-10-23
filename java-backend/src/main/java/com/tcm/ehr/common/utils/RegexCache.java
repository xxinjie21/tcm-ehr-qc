package com.tcm.ehr.common.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.regex.Pattern;

/**
 * 正则表达式编译结果的<b>有界备忘</b>（P2-1）。
 *
 * <p><b>解决什么</b>：格式规则的正则来自配置（QcRuleSet.FormatRule.expr），
 * 原先在 formatOk 里对<b>每条病历、每条规则</b>现编译一次 —— 4 万条病历 × 2 条格式规则
 * 就是 8 万次 Pattern.compile。编译一个正则要解析表达式并构造 NFA，是毫秒级里最贵的
 * 那类常量操作，而表达式在整个运行期是常量。</p>
 *
 * <p><b>为什么是进程内静态备忘而不是「挂版本号的缓存」</b>：这里的映射是<b>纯函数</b>
 * （同一个表达式永远编译出等价的 Pattern），不存在「配置改了缓存还是旧的」这种失效问题 ——
 * 与本项目「配置类一律以 DB 为权威 + 版本号失效」那条约束不冲突：那条约束管的是
 * <b>配置内容</b>的权威来源，而这里只备忘「把这段文本编译成对象」的结果，内容仍来自 DB。</p>
 *
 * <p><b>底层改用 Caffeine</b>（《自研组件替换计划》#4）：原实现是「自写 ConcurrentHashMap
 * + 到界后只读不写」，属重复实现通用能力。换成 {@code Caffeine.maximumSize(512)} 后有两处变化：
 * ① 到界后按 Caffeine 的淘汰策略逐出冷门表达式，而不是「冻住最早写入的 512 条」——
 * 常用表达式反而更稳（不会被早期冷门项永久占位），只在「一个部署里出现过 &gt; 512 条不同格式规则」
 * 时才可能出现重复编译，代价可忽略；② 线程安全与容量判断都交给 Caffeine，删掉手写逻辑。</p>
 *
 * <p><b>非法表达式</b>：{@link Pattern#compile} 会抛，本类<b>不吞</b>它 ——
 * Caffeine 的 {@code get(key, mappingFunction)} 在映射函数抛异常时不写入缓存、原样抛出，
 * 与原来的「先编译、成功了才入表」语义一致。调用方（formatOk）本来就要把
 * 「规则自身写错」与「病历不合规」分开处理（前者放行不扣分），异常必须留给它看见。</p>
 */
public final class RegexCache {

    /** 备忘条数上界 */
    private static final int MAX_ENTRIES = 512;

    private static final Cache<String, Pattern> CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .build();

    private RegexCache() {
    }

    /**
     * 取（必要时编译）一个正则。
     *
     * @param expr 表达式；调用方需保证非 null 非空白（Caffeine 的 get 对 null 键抛 NPE，
     *             与原先 {@code ConcurrentHashMap.get(null)} 同样抛 NPE）
     * @return 编译结果（可能是备忘里复用的实例）
     * @throws java.util.regex.PatternSyntaxException 表达式非法
     */
    public static Pattern of(String expr) {
        return CACHE.get(expr, Pattern::compile);
    }
}
