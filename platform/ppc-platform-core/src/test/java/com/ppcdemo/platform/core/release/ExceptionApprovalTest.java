package com.ppcdemo.platform.core.release;

import com.ppcdemo.platform.core.TaskService;
import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** S4：ε=0 不加噪出库必须全部参与方例外审批，审批后出库为精确值。 */
class ExceptionApprovalTest {

    private Database db;
    private TaskService service;
    private ExceptionApprovalService approvals;
    private TaskRepository tasks;

    @BeforeEach
    void setup() {
        db = Database.inMemory("exc-" + UUID.randomUUID());
        BudgetCenter budget = new BudgetCenter(db);
        AuditCenter audit = new AuditCenter(db);
        approvals = new ExceptionApprovalService(db);
        ReleaseGateway gateway = new ReleaseGateway(db, budget, audit, approvals);
        tasks = new TaskRepository(db);
        service = new TaskService(tasks, audit, gateway, "A");
        budget.ensureAccount("ds://x", 10.0);
    }

    private TaskContract noiselessContract() {
        return new TaskContract(TaskContract.newTaskId(), TaskContract.TaskType.LOCAL_STATS,
                "A", "B", "ds://x", "ds://B/y", null,
                TaskContract.OutputGranularity.NOISED_SCALAR, 0.0, 100_000L, false, null);
    }

    @Test
    void 未审批拒绝_单方审批仍拒绝_双方审批放行精确值() {
        // 未审批 → 拒绝
        TaskContract c1 = noiselessContract();
        service.create(c1);
        tasks.transit(c1.taskId(), TaskState.CREATED, TaskState.READY, null);
        assertThrows(IllegalStateException.class, () -> service.runLocalStats(c1.taskId(), () -> 42L));

        // 仅发起方审批 → 仍拒绝
        TaskContract c2 = noiselessContract();
        service.create(c2);
        tasks.transit(c2.taskId(), TaskState.CREATED, TaskState.READY, null);
        approvals.approve(c2.taskId(), "A", "合规官-甲");
        assertThrows(IllegalStateException.class, () -> service.runLocalStats(c2.taskId(), () -> 42L));

        // 双方审批 → 放行且为精确值（ε=0 不加噪、不扣预算的 0 消耗）
        TaskContract c3 = noiselessContract();
        service.create(c3);
        tasks.transit(c3.taskId(), TaskState.CREATED, TaskState.READY, null);
        approvals.approve(c3.taskId(), "A", "合规官-甲");
        approvals.approve(c3.taskId(), "B", "合规官-乙");
        assertEquals(42.0, service.runLocalStats(c3.taskId(), () -> 42L), 1e-9,
                "例外审批通过后应出库精确值");
        assertEquals(TaskState.SUCCEEDED, service.requireTask(c3.taskId()).state());
    }
}
