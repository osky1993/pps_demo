package com.ppcdemo.platform.core.psi;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;

/**
 * PSI 任务编排（单侧）：READY→RUNNING→RELEASING→SUCCEEDED。
 * 输出粒度治理（M2 §4.2）：CARDINALITY_ONLY 时交集明细文件在登记基数后立即删除，
 * 平台留存的只有基数；INTERSECTION_DETAIL 时明细文件路径入审计。
 * PSI 结果不加噪（契约审批即授权），登记进 release_record 统一走结果出口。
 */
public final class PsiTaskOrchestrator {

    private final Database db;
    private final TaskRepository tasks;
    private final AuditCenter audit;
    private final EngineRunner engine;
    private final String selfParty;

    public PsiTaskOrchestrator(Database db, TaskRepository tasks, AuditCenter audit,
                               EngineRunner engine, String selfParty) {
        this.db = db;
        this.tasks = tasks;
        this.audit = audit;
        this.engine = engine;
        this.selfParty = selfParty;
    }

    /**
     * @param role 本方协议角色（发起方=client 获得交集；数据方=server）
     * @return client 侧返回交集基数；server 侧返回 -1
     */
    public long run(String taskId, String role, long[] ids, EngineRunner.EngineJob jobTemplate) {
        TaskContract contract = tasks.find(taskId).orElseThrow().contract();
        tasks.transit(taskId, TaskState.READY, TaskState.RUNNING, null);
        audit.append(taskId, selfParty, "PSI_STARTED",
                "{\"role\": \"%s\", \"protocol\": \"%s\"}".formatted(role, jobTemplate.protocol()));
        try {
            Path idsFile = Files.createTempFile("psi-ids-" + taskId, ".txt");
            writeIds(idsFile, ids);
            EngineRunner.EngineJob job = new EngineRunner.EngineJob(role, jobTemplate.protocol(),
                    jobTemplate.localEndpoint(), jobTemplate.peerEndpoint(), idsFile,
                    jobTemplate.outputFile(), ids.length, jobTemplate.peerSize());

            EngineRunner.EngineResult result = engine.run(job);
            Files.deleteIfExists(idsFile);
            if (!result.success()) {
                tasks.transit(taskId, TaskState.RUNNING, TaskState.FAILED,
                        "引擎退出码 " + result.exitCode());
                audit.append(taskId, selfParty, "ENGINE_FAILED", trim(result.log()));
                throw new IllegalStateException("PSI 引擎失败：exit=" + result.exitCode());
            }
            audit.append(taskId, selfParty, "PSI_COMPLETED",
                    "{\"elapsedMillis\": %d}".formatted(result.elapsedMillis()));

            tasks.transit(taskId, TaskState.RUNNING, TaskState.RELEASING, null);
            long cardinality = -1;
            if ("client".equals(role)) {
                cardinality = registerResult(contract, job.outputFile());
            }
            tasks.transit(taskId, TaskState.RELEASING, TaskState.SUCCEEDED, null);
            return cardinality;
        } catch (IOException e) {
            throw new UncheckedIOException("PSI 任务文件操作失败", e);
        }
    }

    private long registerResult(TaskContract contract, Path output) throws IOException {
        long cardinality;
        try (var lines = Files.lines(output)) {
            cardinality = lines.filter(l -> !l.isBlank()).count();
        }
        if (contract.outputGranularity() == TaskContract.OutputGranularity.CARDINALITY_ONLY) {
            Files.deleteIfExists(output);   // 最小披露：明细即刻销毁，平台只留基数
            audit.append(contract.taskId(), selfParty, "DETAIL_DESTROYED", "CARDINALITY_ONLY");
        } else {
            audit.append(contract.taskId(), selfParty, "DETAIL_RETAINED",
                    "{\"path\": \"%s\"}".formatted(output));
        }
        long value = cardinality;
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO release_record(task_id, dataset_id, epsilon, released_value, released_at) " +
                    "VALUES (?,?,0,?,?)")) {
                ps.setString(1, contract.taskId());
                ps.setString(2, contract.datasetLocal());
                ps.setDouble(3, value);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            audit.appendInTx(conn, contract.taskId(), selfParty, "RELEASED",
                    "{\"cardinality\": %d, \"grain\": \"%s\"}".formatted(value, contract.outputGranularity()));
            return null;
        });
        return cardinality;
    }

    private static void writeIds(Path file, long[] ids) throws IOException {
        StringBuilder sb = new StringBuilder(ids.length * 20);
        Arrays.stream(ids).forEach(id -> sb.append(id).append('\n'));
        Files.writeString(file, sb);
    }

    private static String trim(String log) {
        return log == null ? null : log.substring(Math.max(0, log.length() - 800));
    }
}
