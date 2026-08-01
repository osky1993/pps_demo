package com.ppcdemo.platform.fate;

import com.ppcdemo.platform.core.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 联邦模型注册表（M3 §4.4）：模型是出库物。状态流 TRAINED → RELEASE_APPROVED → EXPORTED，
 * 状态迁移带前置校验（与任务状态机同风格）。
 */
public final class ModelRegistry {

    public enum ModelState { TRAINED, RELEASE_APPROVED, EXPORTED }

    public record ModelRow(String taskId, String fateJobId, String fateModelId,
                           String fateModelVersion, String algorithm, double auc,
                           String modelSha256, ModelState state) {
    }

    private final Database db;

    public ModelRegistry(Database db) {
        this.db = db;
    }

    public void register(String taskId, String fateJobId, String modelId, String modelVersion,
                         String algorithm, double auc) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    MERGE INTO model_registry(task_id, fate_job_id, fate_model_id, fate_model_version,
                                              algorithm, auc, state, created_at, updated_at)
                    KEY(task_id) VALUES (?,?,?,?,?,?,?,?,?)
                    """)) {
                Timestamp now = Timestamp.from(Instant.now());
                ps.setString(1, taskId);
                ps.setString(2, fateJobId);
                ps.setString(3, modelId);
                ps.setString(4, modelVersion);
                ps.setString(5, algorithm);
                ps.setDouble(6, auc);
                ps.setString(7, ModelState.TRAINED.name());
                ps.setTimestamp(8, now);
                ps.setTimestamp(9, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void transit(String taskId, ModelState from, ModelState to, String sha256) {
        assertLegal(from, to);
        int updated = db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE model_registry SET state=?, model_sha256=COALESCE(?, model_sha256), " +
                    "updated_at=? WHERE task_id=? AND state=?")) {
                ps.setString(1, to.name());
                ps.setString(2, sha256);
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setString(4, taskId);
                ps.setString(5, from.name());
                return ps.executeUpdate();
            }
        });
        if (updated != 1) {
            throw new IllegalStateException(
                    "模型状态迁移失败（状态不符）：%s 期望 %s→%s".formatted(taskId, from, to));
        }
    }

    public Optional<ModelRow> find(String taskId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM model_registry WHERE task_id=?")) {
                ps.setString(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        });
    }

    private static ModelRow map(ResultSet rs) throws SQLException {
        return new ModelRow(rs.getString("task_id"), rs.getString("fate_job_id"),
                rs.getString("fate_model_id"), rs.getString("fate_model_version"),
                rs.getString("algorithm"), rs.getDouble("auc"),
                rs.getString("model_sha256"), ModelState.valueOf(rs.getString("state")));
    }

    private static void assertLegal(ModelState from, ModelState to) {
        boolean legal = (from == ModelState.TRAINED && to == ModelState.RELEASE_APPROVED)
                || (from == ModelState.RELEASE_APPROVED && to == ModelState.EXPORTED);
        if (!legal) {
            throw new IllegalStateException("非法模型状态迁移：" + from + " → " + to);
        }
    }
}
