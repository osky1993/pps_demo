package com.ppcdemo.platform.gateway.quota;

import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.store.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * 应用级日配额（M5 §3.2，G5 双层配额之一）。
 *
 * 与数据集级配额（QueryQuotaCenter）叠加生效：任一耗尽即拒。
 * 复用 BudgetCenter 的行锁事务；账户键含日期 → 天然按日切换（新的一天 = 新账户 = 用量归零）。
 */
public final class AppDailyQuota {

    public static final class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String appId, String capability) {
            super("应用 [%s] 能力 [%s] 今日调用配额已用尽".formatted(appId, capability));
        }
    }

    private final BudgetCenter budget;

    public AppDailyQuota(Database db) {
        this.budget = new BudgetCenter(db);
    }

    public void ensureToday(String appId, String capability, int dailyLimit, LocalDate day) {
        budget.ensureAccount(key(appId, capability, day), dailyLimit);
    }

    /** 在调用方事务内消费 1 次；超限抛 QuotaExceededException。 */
    public void consumeInTx(Connection conn, String appId, String capability,
                            LocalDate day) throws SQLException {
        try {
            budget.consumeInTx(conn, key(appId, capability, day), 1);
        } catch (BudgetCenter.BudgetExhaustedException e) {
            throw new QuotaExceededException(appId, capability);
        }
    }

    public double remainingToday(String appId, String capability, LocalDate day) {
        return budget.remaining(key(appId, capability, day));
    }

    private static String key(String appId, String capability, LocalDate day) {
        return "app-daily:%s:%s:%s".formatted(appId, capability, day);
    }
}
