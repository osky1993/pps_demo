package com.ppcdemo.platform.core.pir;

import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.store.Database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 查询配额中心（M4 治理增量）。
 *
 * PIR 保护「单次查询查了什么」，但**不限制查询次数**——查得足够多等于拖走整库。
 * 这是 PIR 的固有风险，必须在平台层治理：按数据集记账**累计查询条数**，超限熔断。
 *
 * 实现上复用 BudgetCenter 的账户与并发控制（行锁事务）：ε 与查询条数都是
 * 「不可再生的隐私资源」，仅计量单位不同。
 *
 * **诚实边界**：查询条数与真实隐私泄露量之间没有 ε 那样的数学等价——
 * 配额是运营手段而非密码学保证（M4 设计 §4.2，手册与验收报告同步声明）。
 */
public final class QueryQuotaCenter {

    public static final class QuotaExhaustedException extends RuntimeException {
        public QuotaExhaustedException(String dataset, int requested, double remaining) {
            super("数据集 [%s] 查询配额不足：本次申请 %d 条，剩余 %.0f 条"
                    .formatted(dataset, requested, remaining));
        }
    }

    private final Database db;
    private final BudgetCenter budgetCenter;

    public QueryQuotaCenter(Database db) {
        this.db = db;
        this.budgetCenter = new BudgetCenter(db);
    }

    /** 开户：total = 允许查询的总条数。 */
    public void ensureQuota(String datasetId, int totalQueries) {
        budgetCenter.ensureAccount(quotaKey(datasetId), totalQueries);
    }

    /**
     * 查询前预扣（与 DP 的「先扣后噪」同构：失败不扣、超限熔断）。
     * 在调用方事务内执行，使「扣配额 + 记审计」原子。
     */
    public void consumeInTx(Connection conn, String datasetId, int queryCount) throws SQLException {
        if (queryCount <= 0) {
            throw new IllegalArgumentException("查询条数必须为正：" + queryCount);
        }
        try {
            budgetCenter.consumeInTx(conn, quotaKey(datasetId), queryCount);
        } catch (BudgetCenter.BudgetExhaustedException e) {
            throw new QuotaExhaustedException(datasetId, queryCount, remaining(datasetId));
        }
    }

    /** 便捷入口：独立事务内预扣。 */
    public void consume(String datasetId, int queryCount) {
        db.inTransaction(conn -> {
            consumeInTx(conn, datasetId, queryCount);
            return null;
        });
    }

    public double remaining(String datasetId) {
        return budgetCenter.remaining(quotaKey(datasetId));
    }

    /** 与 DP 预算共用 budget_account 表，以前缀区分资源类型，避免语义串账。 */
    private static String quotaKey(String datasetId) {
        return "pir-quota:" + datasetId;
    }
}
