package com.ppcdemo.platform.core;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.release.ReleaseGateway;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 闭环（CP1）：单节点「本地数据集 DP 出库」全生命周期，
 * 含预算耗尽拒绝、审计链完整、真实值不出网关。
 */
class LocalStatsFlowTest {

    private Database db;
    private TaskService service;
    private ReleaseGateway gateway;
    private AuditCenter audit;
    private BudgetCenter budget;

    @BeforeEach
    void setup() {
        db = Database.inMemory("flow-" + UUID.randomUUID());
        budget = new BudgetCenter(db);
        audit = new AuditCenter(db);
        gateway = new ReleaseGateway(db, budget, audit);
        service = new TaskService(new TaskRepository(db), audit, gateway, "A");
        budget.ensureAccount("ds://local/tx", 2.0);
    }

    private TaskContract newContract() {
        return new TaskContract(TaskContract.newTaskId(), TaskContract.TaskType.LOCAL_STATS,
                "A", null, "ds://local/tx", null, null,
                TaskContract.OutputGranularity.NOISED_SCALAR, 1.0, 100_000L, false, null);
    }

    @Test
    void 全生命周期成功路径() {
        String taskId = service.create(newContract());
        long trueValue = 5_000_000L;
        double released = service.runLocalStats(taskId, () -> trueValue);

        assertEquals(TaskState.SUCCEEDED, service.requireTask(taskId).state());
        // ε=1、敏感度 1e5：噪声尺度 b=1e5，|noise|>20b 概率 e^-20≈2e-9，断言统计稳健
        assertTrue(Math.abs(released - trueValue) < 20 * 100_000L, "出库值偏差异常：" + released);
        assertEquals(released, gateway.releasedValue(taskId).orElseThrow(), 1e-9,
                "对外结果必须与 release_record 一致");
        assertEquals(1.0, budget.remaining("ds://local/tx"), 1e-9);
        assertTrue(audit.verifyChain());
        assertTrue(audit.byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("RELEASED")), "出库必须留审计");
    }

    @Test
    void 预算耗尽时出库被拒且任务进REJECTED() {
        String first = service.create(newContract());
        String second = service.create(newContract());
        String third = service.create(newContract());
        service.runLocalStats(first, () -> 100L);
        service.runLocalStats(second, () -> 100L);   // 预算 2.0 耗尽

        assertThrows(BudgetCenter.BudgetExhaustedException.class,
                () -> service.runLocalStats(third, () -> 100L));
        assertEquals(TaskState.REJECTED, service.requireTask(third).state());
        assertTrue(gateway.releasedValue(third).isEmpty(), "被拒任务不得有出库记录");
        assertEquals(0.0, budget.remaining("ds://local/tx"), 1e-9, "失败不扣减");
    }

    @Test
    void 计算失败进FAILED可重跑() {
        String taskId = service.create(newContract());
        assertThrows(IllegalStateException.class,
                () -> service.runLocalStats(taskId, () -> {
                    throw new IllegalStateException("数据源不可用");
                }));
        assertEquals(TaskState.FAILED, service.requireTask(taskId).state());

        // 幂等重跑：FAILED→READY 后重新执行成功，且预算只扣一次
        new TaskRepository(db).transit(taskId, TaskState.FAILED, TaskState.READY, null);
        service.runLocalStats(taskId, () -> 42L);
        assertEquals(TaskState.SUCCEEDED, service.requireTask(taskId).state());
        assertEquals(1.0, budget.remaining("ds://local/tx"), 1e-9, "重跑成功仅扣一次预算");
    }

    @Test
    void 不加噪例外未审批被网关拒绝() {
        TaskContract noiseless = new TaskContract(TaskContract.newTaskId(),
                TaskContract.TaskType.LOCAL_STATS, "A", null, "ds://local/tx", null, null,
                TaskContract.OutputGranularity.NOISED_SCALAR, 0.0, 100_000L, false, null);
        String taskId = service.create(noiseless);
        assertThrows(IllegalStateException.class, () -> service.runLocalStats(taskId, () -> 42L));
        assertEquals(TaskState.REJECTED, service.requireTask(taskId).state());
    }
}
