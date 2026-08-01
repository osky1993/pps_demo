package com.ppcdemo.platform.core.domain;

import java.util.Map;
import java.util.Set;

/**
 * 任务状态机（M2 §4.1）。RELEASING 独立成态：出库是治理关口，失败语义与计算失败不同。
 */
public enum TaskState {
    CREATED, NEGOTIATING, READY, RUNNING, RELEASING, SUCCEEDED, FAILED, REJECTED, CANCELLED;

    private static final Map<TaskState, Set<TaskState>> LEGAL = Map.of(
            CREATED, Set.of(NEGOTIATING, READY, CANCELLED),      // 单方任务免协商直达 READY
            NEGOTIATING, Set.of(READY, REJECTED, CANCELLED),
            READY, Set.of(RUNNING, CANCELLED),
            RUNNING, Set.of(RELEASING, FAILED),
            RELEASING, Set.of(SUCCEEDED, REJECTED, FAILED),      // REJECTED=DP 网关拒绝（预算不足等）
            SUCCEEDED, Set.of(),
            FAILED, Set.of(READY),                               // 幂等重跑：回到 READY
            REJECTED, Set.of(),
            CANCELLED, Set.of());

    public void assertCanTransitTo(TaskState next) {
        if (!LEGAL.get(this).contains(next)) {
            throw new IllegalStateException("非法状态迁移：" + this + " → " + next);
        }
    }

    public boolean terminal() {
        return LEGAL.get(this).isEmpty();
    }
}
