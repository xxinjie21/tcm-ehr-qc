package com.tcm.ehr.common.utils;

/**
 * 分页条数收敛：把「每页条数」钳到合理区间。
 *
 * <p>不加约束时 {@code pageSize=999999} 会直接变成 {@code LIMIT 999999}，
 * 一次把整表读进内存 —— 日志表、复核任务表都是只增不减的，几十万行时足以拖垮进程。
 * 故所有分页接口统一经 {@link #clamp(int)} 收口，<b>只在这里定义一次</b>，
 * 避免每个 Controller 各写一遍上限导致口径漂移。</p>
 */
public final class PageSizeGuard {

    /** 单页上限：与前端最大可选页长一致（el-pagination 最大 200，留出余量） */
    public static final int MAX_PAGE_SIZE = 500;

    /** 单页下限：小于 1 的值没有意义（SQL 会拼出 LIMIT 0/-1） */
    public static final int MIN_PAGE_SIZE = 1;

    private PageSizeGuard() {
    }

    /**
     * 收敛每页条数。
     *
     * @param pageSize 调用方传入的每页条数，可为 null / 0 / 负数 / 超大值
     * @return 落在 [{@value #MIN_PAGE_SIZE}, {@value #MAX_PAGE_SIZE}] 区间内的值
     */
    public static int clamp(Integer pageSize) {
        if (pageSize == null) {
            return MIN_PAGE_SIZE;
        }
        return Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, pageSize));
    }
}
