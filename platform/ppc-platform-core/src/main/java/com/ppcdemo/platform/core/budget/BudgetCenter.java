package com.ppcdemo.platform.core.budget;

import com.ppcdemo.platform.core.store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 隐私预算中心（M1 BudgetLedger 的持久化升级，语义不变：先扣减后加噪、失败不扣、数据集隔离）。
 * 扣减用 SELECT ... FOR UPDATE 行锁，并发扣减不会超卖。
 */
public final class BudgetCenter {

    public static final class BudgetExhaustedException extends RuntimeException {
        public BudgetExhaustedException(String dataset, double requested, double remaining) {
            super("数据集 [%s] 隐私预算不足：本次申请 ε=%s，剩余 ε=%s".formatted(dataset, requested, remaining));
        }
    }

    private final Database db;

    public BudgetCenter(Database db) {
        this.db = db;
    }

    /** 开户（幂等）：已存在则不变。 */
    public void ensureAccount(String datasetId, double total) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO budget_account(dataset_id, total, spent) KEY(dataset_id) " +
                    "VALUES (?, ?, COALESCE((SELECT spent FROM budget_account WHERE dataset_id=?), 0))")) {
                ps.setString(1, datasetId);
                ps.setDouble(2, total);
                ps.setString(3, datasetId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 在调用方事务内扣减（出库网关将预算扣减与出库记录放同一事务）。 */
    public void consumeInTx(Connection conn, String datasetId, double epsilon) throws SQLException {
        if (epsilon < 0) {
            throw new IllegalArgumentException("ε 不能为负：" + epsilon);
        }
        try (PreparedStatement lock = conn.prepareStatement(
                "SELECT total, spent FROM budget_account WHERE dataset_id=? FOR UPDATE")) {
            lock.setString(1, datasetId);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("数据集未开预算账户：" + datasetId);
                }
                double total = rs.getDouble("total");
                double spent = rs.getDouble("spent");
                if (spent + epsilon > total) {
                    throw new BudgetExhaustedException(datasetId, epsilon, total - spent);
                }
            }
        }
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE budget_account SET spent = spent + ? WHERE dataset_id=?")) {
            update.setDouble(1, epsilon);
            update.setString(2, datasetId);
            update.executeUpdate();
        }
    }

    public double remaining(String datasetId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT total - spent FROM budget_account WHERE dataset_id=?")) {
                ps.setString(1, datasetId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("数据集未开预算账户：" + datasetId);
                    }
                    return rs.getDouble(1);
                }
            }
        });
    }
}
