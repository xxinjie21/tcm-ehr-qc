package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批B·2.3：LogicChecker 规则（3 证候 + 2 舌脉；未覆盖不判冲突）。
 */
class LogicCheckerTest {

    @Test
    void consistentCase() {
        List<String> conflicts = LogicChecker.check(
                List.of("风寒感冒"), List.of("辛温解表"), List.of("麻黄汤"),
                List.of("舌淡红"), List.of("脉浮紧"));
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void treatmentMismatch() {
        List<String> conflicts = LogicChecker.check(
                List.of("风寒感冒"), List.of("辛凉解表"), List.of("麻黄汤"),
                List.of(), List.of());
        assertTrue(conflicts.stream().anyMatch(c -> c.startsWith(LogicChecker.TYPE_TREATMENT)));
    }

    @Test
    void formulaMismatch() {
        List<String> conflicts = LogicChecker.check(
                List.of("风热感冒"), List.of("辛凉解表"), List.of("麻黄汤"),
                List.of(), List.of());
        assertTrue(conflicts.stream().anyMatch(c -> c.startsWith(LogicChecker.TYPE_FORMULA)));
    }

    @Test
    void tonguePulseConflict() {
        List<String> conflicts = LogicChecker.check(
                List.of(), List.of(), List.of(), List.of("舌红"), List.of("脉沉迟"));
        assertTrue(conflicts.stream().anyMatch(c -> c.startsWith(LogicChecker.TYPE_TONGUE_PULSE)));
    }

    @Test
    void uncoveredPatternNoConflict() {
        List<String> conflicts = LogicChecker.check(
                List.of("肝肾亏虚"), List.of("随意治法"), List.of("随意方"),
                List.of("舌红"), List.of("脉沉迟"));
        assertFalse(conflicts.stream().anyMatch(c -> c.startsWith(LogicChecker.TYPE_TREATMENT)));
    }
}
