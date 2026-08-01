package com.ppcdemo.platform.core.release;

import com.google.privacy.differentialprivacy.LaplaceNoise;
import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * DP 出库网关（M2 §4.4）：平台对外结果的唯一写入方。
 * 单一事务内完成：预算扣减（行锁）→ 加噪 → 写 release_record → 审计。
 * 任一环节失败整体回滚（预算不白扣、结果不半出）。
 * epsilon=0（不加噪例外）默认拒绝，S4 例外审批放行后才允许。
 */
public final class ReleaseGateway {

    private final Database db;
    private final BudgetCenter budgetCenter;
    private final AuditCenter auditCenter;
    private final ExceptionApprovalService exceptionApprovals;

    public ReleaseGateway(Database db, BudgetCenter budgetCenter, AuditCenter auditCenter) {
        this(db, budgetCenter, auditCenter, new ExceptionApprovalService(db));
    }

    public ReleaseGateway(Database db, BudgetCenter budgetCenter, AuditCenter auditCenter,
                          ExceptionApprovalService exceptionApprovals) {
        this.db = db;
        this.budgetCenter = budgetCenter;
        this.auditCenter = auditCenter;
        this.exceptionApprovals = exceptionApprovals;
    }

    /** 例外审批检查内置于网关：治理逻辑不依赖调用方自觉（S4 起唯一签名）。 */
    public double release(TaskContract contract, long trueValue) {
        double epsilon = contract.epsilon();
        if (contract.isNoiselessException() && !exceptionApprovals.fullyApproved(contract)) {
            throw new IllegalStateException("epsilon=0 不加噪出库未获全部参与方例外审批，网关拒绝");
        }
        return db.inTransaction(conn -> {
            budgetCenter.consumeInTx(conn, contract.datasetLocal(), epsilon);
            double released = epsilon == 0.0
                    ? trueValue
                    : new LaplaceNoise().addNoise(trueValue, contract.clampUpper(), epsilon, null);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO release_record(task_id, dataset_id, epsilon, released_value, released_at) " +
                    "VALUES (?,?,?,?,?)")) {
                ps.setString(1, contract.taskId());
                ps.setString(2, contract.datasetLocal());
                ps.setDouble(3, epsilon);
                ps.setDouble(4, released);
                ps.setTimestamp(5, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            auditCenter.appendInTx(conn, contract.taskId(), contract.initiator(), "RELEASED",
                    "{\"epsilon\": %s, \"noiseless\": %s}".formatted(epsilon, epsilon == 0.0));
            return released;
        });
    }

    /** 对外结果读取的唯一合法来源。 */
    public Optional<Double> releasedValue(String taskId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT released_value FROM release_record WHERE task_id=?")) {
                ps.setString(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getDouble(1)) : Optional.empty();
                }
            }
        });
    }
}
