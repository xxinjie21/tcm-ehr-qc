package com.tcm.ehr.common.utils;

import java.time.LocalDateTime;

/**
 * 复核任务工具（批D·5.1）。
 *
 * <p><b>无后台定时任务</b>：超时仅由前端/出参计算属性做视觉提醒，不自动流转任务状态。</p>
 */
public final class ReviewTaskUtil {

    private ReviewTaskUtil() {
    }

    /** 是否已超复核截止时间（deadlineTime 为空视为未超时） */
    public static boolean isOverdue(LocalDateTime deadlineTime) {
        return deadlineTime != null && LocalDateTime.now().isAfter(deadlineTime);
    }
}
