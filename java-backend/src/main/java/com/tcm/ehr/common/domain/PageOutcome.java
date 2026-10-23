package com.tcm.ehr.common.domain;

/**
 * 批量任务「一页处理完毕」的结果判定。
 *
 * 改造前三态缺失，外层把布尔返回值一会儿理解成「已取消」、一会儿理解成「继续翻页」，
 * 而 processPage 还有「已达 limit 上限」这个出口也返回同一值 ——
 * 于是 limit 触顶后外层继续翻页，把整个匹配集翻完（total=0 的任务同样如此）。
 * 用枚举把这个歧义消掉。
 *
 * 注意：ABORTED 与 CANCELED 必须分开 —— 停机不是用户的取消意图，不能落 CANCELLED。
 */
public enum PageOutcome {

    /** 本页处理完毕，未取消、未触顶 */
    CONTINUE,

    /** 检测到取消请求 */
    CANCELED,

    /** 已达 limit 硬上限，后续页一条都不该再处理 */
    LIMIT_REACHED,

    /**
     * 应用正在停机、任务内并行池已拒绝提交。
     *
     * 刻意与 CANCELED 分开：这不是用户的取消意图，不能落 CANCELLED。
     * 调用方按「未被取消」收尾，由 endStatus 依「已停机 + 未跑完」落 INTERRUPTED。
     */
    ABORTED
}
