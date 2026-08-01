package com.ppcdemo.dp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隐私预算记账（内存演示版）：按数据集记 ε 累计消耗，超限熔断。
 * M2 隐私预算中心的接口原型（M1 设计文档 §5.2、§10）——
 * 持久化、审批例外、审计留痕都是 M2 的事，本类只验证记账语义。
 */
public final class BudgetLedger {

    public static final class BudgetExhaustedException extends RuntimeException {
        BudgetExhaustedException(String dataset, double requested, double remaining) {
            super("数据集 [%s] 隐私预算不足：本次申请 ε=%s，剩余 ε=%s".formatted(dataset, requested, remaining));
        }
    }

    private final double budgetTotal;
    private final Map<String, Double> spentByDataset = new ConcurrentHashMap<>();

    public BudgetLedger(double budgetTotal) {
        if (budgetTotal <= 0) {
            throw new IllegalArgumentException("预算总额必须为正：" + budgetTotal);
        }
        this.budgetTotal = budgetTotal;
    }

    /** 申请消耗；不足则抛 BudgetExhaustedException，且不产生任何扣减。 */
    public void consume(String dataset, double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("ε 必须为正：" + epsilon);
        }
        spentByDataset.compute(dataset, (k, spent) -> {
            double used = spent == null ? 0 : spent;
            if (used + epsilon > budgetTotal) {
                throw new BudgetExhaustedException(dataset, epsilon, budgetTotal - used);
            }
            return used + epsilon;
        });
    }

    public double remaining(String dataset) {
        return budgetTotal - spentByDataset.getOrDefault(dataset, 0.0);
    }
}
