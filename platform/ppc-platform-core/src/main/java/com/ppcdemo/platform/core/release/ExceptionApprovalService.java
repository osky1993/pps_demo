package com.ppcdemo.platform.core.release;

import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * ε=0 不加噪出库的例外审批（M2 §4.4）：契约全部参与方逐一审批后网关才放行。
 * 审批本身落审计（由调用方在 approve 后追加事件）。
 */
public final class ExceptionApprovalService {

    private final Database db;

    public ExceptionApprovalService(Database db) {
        this.db = db;
    }

    public void approve(String taskId, String party, String approver) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO exception_approval(task_id, party, approver, approved_at) " +
                    "KEY(task_id, party) VALUES (?,?,?,?)")) {
                ps.setString(1, taskId);
                ps.setString(2, party);
                ps.setString(3, approver);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 契约全部参与方（发起方 + 响应方，若有）都已审批。 */
    public boolean fullyApproved(TaskContract contract) {
        Set<String> required = new HashSet<>();
        required.add(contract.initiator());
        if (contract.responder() != null) {
            required.add(contract.responder());
        }
        Set<String> approved = db.inTransaction(conn -> {
            Set<String> parties = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT party FROM exception_approval WHERE task_id=?")) {
                ps.setString(1, contract.taskId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        parties.add(rs.getString(1));
                    }
                }
            }
            return parties;
        });
        return approved.containsAll(required);
    }
}
