package com.ppcdemo.platform.gateway.idem;

import com.ppcdemo.platform.core.store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等台账（pps_request，M5 §3.3，G4）。
 *
 * 隐私计算的副作用不可逆（预算扣了就是扣了、对方已看到协商请求），
 * 故幂等是**正确性要求**而非优化。claim 在调用方事务内执行——与配额预扣同事务，
 * 保证「认领 + 扣配额」原子。
 */
public final class IdempotencyStore {

    public enum ReqState { ACCEPTED, SUCCEEDED, FAILED }

    public record Record(String appId, String reqId, String capability, String taskId,
                         ReqState state, String resultRef) {
    }

    /** claim 结果：fresh=true 表示本次首次认领；否则 existing 为已有记录（重放）。 */
    public record Claim(boolean fresh, Record existing) {
    }

    private final Database db;

    public IdempotencyStore(Database db) {
        this.db = db;
    }

    /**
     * 在调用方事务内认领 reqId：INSERT-first（先插入者胜）。
     * 冲突时读取既有记录返回（并发者会阻塞至先入者提交，保证串行化）。
     */
    public Claim claimInTx(Connection conn, String appId, String reqId,
                           String capability) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pps_request(app_id, req_id, capability, state, created_at) " +
                "VALUES (?,?,?,?,?)")) {
            ps.setString(1, appId);
            ps.setString(2, reqId);
            ps.setString(3, capability);
            ps.setString(4, ReqState.ACCEPTED.name());
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return new Claim(true, null);
        } catch (SQLException dup) {
            // 主键冲突 = 已被认领；读取既有记录
            Record existing = select(conn, appId, reqId)
                    .orElseThrow(() -> new SQLException("认领冲突但无既有记录", dup));
            return new Claim(false, existing);
        }
    }

    /** 执行完成后落终态（独立事务）。 */
    public void complete(String appId, String reqId, ReqState state, String resultRef) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE pps_request SET state=?, result_ref=? WHERE app_id=? AND req_id=?")) {
                ps.setString(1, state.name());
                ps.setString(2, resultRef);
                ps.setString(3, appId);
                ps.setString(4, reqId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Record> find(String appId, String reqId) {
        return db.inTransaction(conn -> select(conn, appId, reqId));
    }

    private Optional<Record> select(Connection conn, String appId, String reqId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pps_request WHERE app_id=? AND req_id=?")) {
            ps.setString(1, appId);
            ps.setString(2, reqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Record(rs.getString("app_id"), rs.getString("req_id"),
                        rs.getString("capability"), rs.getString("task_id"),
                        ReqState.valueOf(rs.getString("state")), rs.getString("result_ref")));
            }
        }
    }
}
