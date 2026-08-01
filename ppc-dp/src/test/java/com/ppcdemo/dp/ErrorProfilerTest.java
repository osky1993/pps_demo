package com.ppcdemo.dp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorProfilerTest {

    @Test
    void ε越大误差越小() {
        long trueCount = 1_000_000;
        var profiles = new ErrorProfiler.ErrorProfile[2];
        double[] epsilons = {0.1, 10.0};
        for (int i = 0; i < epsilons.length; i++) {
            double epsilon = epsilons[i];
            BudgetLedger ledger = new BudgetLedger(epsilon * 500 + 1);
            DpAggregator dp = new DpAggregator(ledger);
            profiles[i] = ErrorProfiler.profile(trueCount,
                    () -> dp.noisedCount("t", trueCount, epsilon), 400);
        }
        // ε=0.1 的 Laplace 噪声尺度是 ε=10 的 100 倍，P95 分位差距统计上必然显著
        assertTrue(profiles[0].p95() > profiles[1].p95() * 5,
                "ε=0.1 的误差应显著大于 ε=10：%s vs %s".formatted(profiles[0], profiles[1]));
    }
}
