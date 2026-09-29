package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link PageSizeGuard} 的边界用例：pageSize=999999 是本项要拦的原始动机。 */
class PageSizeGuardTest {

    @Test
    void hugePageSize_shouldClampToMax() {
        assertEquals(PageSizeGuard.MAX_PAGE_SIZE, PageSizeGuard.clamp(999999));
    }

    @Test
    void negativeAndZero_shouldClampToMin() {
        assertEquals(PageSizeGuard.MIN_PAGE_SIZE, PageSizeGuard.clamp(0));
        assertEquals(PageSizeGuard.MIN_PAGE_SIZE, PageSizeGuard.clamp(-5));
    }

    @Test
    void null_shouldClampToMin() {
        assertEquals(PageSizeGuard.MIN_PAGE_SIZE, PageSizeGuard.clamp(null));
    }

    @Test
    void normalValue_shouldPassThrough() {
        assertEquals(20, PageSizeGuard.clamp(20));
        assertEquals(PageSizeGuard.MAX_PAGE_SIZE, PageSizeGuard.clamp(PageSizeGuard.MAX_PAGE_SIZE));
    }
}
