package com.ppcdemo.platform.core.psi;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S4：引擎失败 → FAILED → 幂等重跑成功，出库记录唯一（引擎用桩，不起进程）。 */
class PsiRetryFlowTest {

    @Test
    void 引擎失败后重跑成功且出库唯一() throws IOException {
        Database db = Database.inMemory("retry-" + UUID.randomUUID());
        TaskRepository tasks = new TaskRepository(db);
        AuditCenter audit = new AuditCenter(db);
        Path output = Files.createTempDirectory("retry").resolve("out.txt");

        AtomicInteger attempts = new AtomicInteger();
        EngineRunner flakyEngine = job -> {
            if (attempts.incrementAndGet() == 1) {
                return new EngineRunner.EngineResult(2, 10, "首跑模拟网络中断");
            }
            try {
                Files.writeString(job.outputFile(), "11\n22\n33\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new EngineRunner.EngineResult(0, 10, "ok");
        };

        String taskId = "retry-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.PSI, "A", "B",
                "ds://A/ids", "ds://B/ids", "rr22",
                TaskContract.OutputGranularity.CARDINALITY_ONLY, null, null, false, null);
        tasks.insert(contract, TaskState.READY);
        PsiTaskOrchestrator orchestrator = new PsiTaskOrchestrator(db, tasks, audit, flakyEngine, "A");
        var job = new EngineRunner.EngineJob("client", "rr22", "l", "p", null, output, 0, 3);

        // 首跑失败 → FAILED
        assertThrows(IllegalStateException.class,
                () -> orchestrator.run(taskId, "client", new long[]{11, 22, 33}, job));
        assertEquals(TaskState.FAILED, tasks.find(taskId).orElseThrow().state());

        // 幂等重跑：FAILED→READY→成功
        tasks.transit(taskId, TaskState.FAILED, TaskState.READY, null);
        long cardinality = orchestrator.run(taskId, "client", new long[]{11, 22, 33}, job);
        assertEquals(3, cardinality);
        assertEquals(TaskState.SUCCEEDED, tasks.find(taskId).orElseThrow().state());
        assertTrue(audit.verifyChain());
        assertEquals(2, attempts.get());

        // 出库记录唯一（重跑不产生重复记录——主键约束兜底）
        long releaseCount = db.inTransaction(conn -> {
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM release_record WHERE task_id=?")) {
                ps.setString(1, taskId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        assertEquals(1, releaseCount);
    }
}
