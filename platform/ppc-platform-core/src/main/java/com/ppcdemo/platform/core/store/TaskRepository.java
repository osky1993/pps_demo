package com.ppcdemo.platform.core.store;

import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** task 表存取。状态迁移带前置状态校验（乐观并发 + 状态机双保险）。 */
public final class TaskRepository {

    public record TaskRow(TaskContract contract, TaskState state, String failReason) {
    }

    private final Database db;

    public TaskRepository(Database db) {
        this.db = db;
    }

    public void insert(TaskContract c, TaskState initial) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO task(task_id, task_type, state, initiator, responder, dataset_local,
                                     dataset_peer, protocol, output_grain, epsilon, clamp_upper,
                                     batch_leakage_accepted, contract_fingerprint, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                Timestamp now = Timestamp.from(Instant.now());
                ps.setString(1, c.taskId());
                ps.setString(2, c.taskType().name());
                ps.setString(3, initial.name());
                ps.setString(4, c.initiator());
                ps.setString(5, c.responder());
                ps.setString(6, c.datasetLocal());
                ps.setString(7, c.datasetPeer());
                ps.setString(8, c.protocol());
                ps.setString(9, c.outputGranularity().name());
                ps.setObject(10, c.epsilon());
                ps.setObject(11, c.clampUpper());
                ps.setBoolean(12, c.batchLeakageAccepted());
                ps.setString(13, c.contractFingerprint());
                ps.setTimestamp(14, now);
                ps.setTimestamp(15, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 状态迁移：状态机合法性 + DB 侧前置状态匹配（并发下丢失更新即失败）。 */
    public void transit(String taskId, TaskState from, TaskState to, String failReason) {
        from.assertCanTransitTo(to);
        int updated = db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE task SET state=?, fail_reason=?, updated_at=? WHERE task_id=? AND state=?")) {
                ps.setString(1, to.name());
                ps.setString(2, failReason);
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setString(4, taskId);
                ps.setString(5, from.name());
                return ps.executeUpdate();
            }
        });
        if (updated != 1) {
            throw new IllegalStateException(
                    "状态迁移失败（并发修改或状态不符）：%s 期望 %s→%s".formatted(taskId, from, to));
        }
    }

    public Optional<TaskRow> find(String taskId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM task WHERE task_id=?")) {
                ps.setString(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    private static TaskRow mapRow(ResultSet rs) throws SQLException {
        TaskContract contract = new TaskContract(
                rs.getString("task_id"),
                TaskContract.TaskType.valueOf(rs.getString("task_type")),
                rs.getString("initiator"),
                rs.getString("responder"),
                rs.getString("dataset_local"),
                rs.getString("dataset_peer"),
                rs.getString("protocol"),
                TaskContract.OutputGranularity.valueOf(rs.getString("output_grain")),
                (Double) rs.getObject("epsilon"),
                (Long) rs.getObject("clamp_upper"),
                rs.getBoolean("batch_leakage_accepted"),
                rs.getString("contract_fingerprint"));
        return new TaskRow(contract, TaskState.valueOf(rs.getString("state")), rs.getString("fail_reason"));
    }
}
