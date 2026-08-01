package com.ppcdemo.platform.gateway.job;

import com.ppcdemo.platform.core.store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 异步作业仓储（pps_request 的异步列 + pps_job_result 大结果分页，M5-P2）。
 *
 * 结果三档（G6）：
 * - INLINE：小结果（≤ INLINE_MAX_CHARS）存 result_ref，getResult 直接内联；
 * - PAGED ：大结果逐行存 pps_job_result，按 line_no 分页拉取；
 * - REF   ：更大结果的对象存储签名 URL（本 demo 以 PAGED 的受控访问等价，手册注明）。
 */
public final class JobRepository {

    public static final int INLINE_MAX_CHARS = 8_192;

    public enum JobState { ACCEPTED, RUNNING, SUCCEEDED, FAILED }

    public enum ResultKind { INLINE, PAGED, REF }

    public record JobRecord(String appId, String reqId, String capability, JobState state,
                            ResultKind resultKind, String resultRef, String callbackUrl,
                            String failReason) {
    }

    public record ResultPage(List<String> lines, int nextCursor, boolean hasMore) {
    }

    private final Database db;

    public JobRepository(Database db) {
        this.db = db;
    }

    /** 在认领事务内补记回调地址（与幂等认领 + 配额同事务）。 */
    public void setCallbackInTx(Connection conn, String appId, String reqId,
                                String callbackUrl) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE pps_request SET callback_url=? WHERE app_id=? AND req_id=?")) {
            ps.setString(1, callbackUrl);
            ps.setString(2, appId);
            ps.setString(3, reqId);
            ps.executeUpdate();
        }
    }

    public void markRunning(String appId, String reqId, String taskId) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE pps_request SET state='RUNNING', task_id=? WHERE app_id=? AND req_id=?")) {
                ps.setString(1, taskId);
                ps.setString(2, appId);
                ps.setString(3, reqId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 完成：按结果规模自动选档（INLINE / PAGED），单事务落状态与结果。 */
    public ResultKind complete(String appId, String reqId, String summary, List<String> lines) {
        String joined = String.join("\n", lines);
        ResultKind kind = joined.length() <= INLINE_MAX_CHARS ? ResultKind.INLINE : ResultKind.PAGED;
        db.inTransaction(conn -> {
            if (kind == ResultKind.PAGED) {
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO pps_job_result(app_id, req_id, line_no, line) VALUES (?,?,?,?)")) {
                    for (int i = 0; i < lines.size(); i++) {
                        ins.setString(1, appId);
                        ins.setString(2, reqId);
                        ins.setInt(3, i);
                        ins.setString(4, lines.get(i));
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE pps_request SET state='SUCCEEDED', result_kind=?, result_ref=? " +
                    "WHERE app_id=? AND req_id=?")) {
                ps.setString(1, kind.name());
                ps.setString(2, kind == ResultKind.INLINE ? joined : summary);
                ps.setString(3, appId);
                ps.setString(4, reqId);
                ps.executeUpdate();
            }
            return null;
        });
        return kind;
    }

    public void fail(String appId, String reqId, String reason) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE pps_request SET state='FAILED', fail_reason=? WHERE app_id=? AND req_id=?")) {
                ps.setString(1, reason == null ? "" : reason.substring(0, Math.min(1000, reason.length())));
                ps.setString(2, appId);
                ps.setString(3, reqId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<JobRecord> find(String appId, String reqId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM pps_request WHERE app_id=? AND req_id=?")) {
                ps.setString(1, appId);
                ps.setString(2, reqId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String rk = rs.getString("result_kind");
                    return Optional.of(new JobRecord(rs.getString("app_id"), rs.getString("req_id"),
                            rs.getString("capability"), JobState.valueOf(rs.getString("state")),
                            rk == null ? null : ResultKind.valueOf(rk),
                            rs.getString("result_ref"), rs.getString("callback_url"),
                            rs.getString("fail_reason")));
                }
            }
        });
    }

    /** 分页拉取 PAGED 结果（cursor = 起始 line_no）。 */
    public ResultPage readResult(String appId, String reqId, int cursor, int limit) {
        return db.inTransaction(conn -> {
            List<String> lines = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT line_no, line FROM pps_job_result WHERE app_id=? AND req_id=? " +
                    "AND line_no>=? ORDER BY line_no LIMIT ?")) {
                ps.setString(1, appId);
                ps.setString(2, reqId);
                ps.setInt(3, cursor);
                ps.setInt(4, limit + 1);   // 多取 1 行判断是否还有
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString("line"));
                    }
                }
            }
            boolean hasMore = lines.size() > limit;
            if (hasMore) {
                lines.remove(lines.size() - 1);
            }
            return new ResultPage(lines, cursor + lines.size(), hasMore);
        });
    }
}
