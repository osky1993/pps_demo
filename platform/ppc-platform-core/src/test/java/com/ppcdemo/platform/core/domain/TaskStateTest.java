package com.ppcdemo.platform.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateTest {

    @Test
    void 合法迁移放行() {
        assertDoesNotThrow(() -> TaskState.CREATED.assertCanTransitTo(TaskState.NEGOTIATING));
        assertDoesNotThrow(() -> TaskState.RUNNING.assertCanTransitTo(TaskState.RELEASING));
        assertDoesNotThrow(() -> TaskState.FAILED.assertCanTransitTo(TaskState.READY), "失败可幂等重跑");
    }

    @Test
    void 非法迁移拒绝() {
        assertThrows(IllegalStateException.class,
                () -> TaskState.CREATED.assertCanTransitTo(TaskState.SUCCEEDED), "不得跳过执行直达成功");
        assertThrows(IllegalStateException.class,
                () -> TaskState.SUCCEEDED.assertCanTransitTo(TaskState.RUNNING), "终态不可逆");
        assertThrows(IllegalStateException.class,
                () -> TaskState.RUNNING.assertCanTransitTo(TaskState.SUCCEEDED), "必须经过 RELEASING 治理关口");
    }

    @Test
    void 终态判定() {
        assertTrue(TaskState.SUCCEEDED.terminal());
        assertTrue(TaskState.REJECTED.terminal());
    }
}
