package com.ppcdemo.dp;

import com.google.privacy.differentialprivacy.BoundedMean;
import com.google.privacy.differentialprivacy.BoundedSum;
import com.google.privacy.differentialprivacy.Count;
import com.google.privacy.differentialprivacy.LaplaceNoise;

/**
 * Google DP 原语的薄封装：每次调用先向 BudgetLedger 申请 ε，通过后才加噪。
 * 出库唯一通道的语义雏形（总体设计 §3.4：所有出库结果强制过 DP 网关）。
 * Google DP 的聚合对象是一次性的，因此本类每次调用新建实例。
 */
public final class DpAggregator {

    private final BudgetLedger ledger;

    public DpAggregator(BudgetLedger ledger) {
        this.ledger = ledger;
    }

    public double noisedCount(String dataset, long trueCount, double epsilon) {
        ledger.consume(dataset, epsilon);
        Count count = Count.builder()
                .epsilon(epsilon)
                .maxPartitionsContributed(1)
                .build();
        count.incrementBy(trueCount);
        return count.computeResult();
    }

    /** @param clampLower/@param clampUpper 单条记录贡献界，值超界会被截断（截断偏差 vs 噪声大小的权衡见设计文档 §5.2） */
    public double noisedSum(String dataset, long[] values, double epsilon, long clampLower, long clampUpper) {
        ledger.consume(dataset, epsilon);
        BoundedSum sum = BoundedSum.builder()
                .epsilon(epsilon)
                .lower(clampLower)
                .upper(clampUpper)
                .maxPartitionsContributed(1)
                .build();
        for (long v : values) {
            sum.addEntry(v);
        }
        return sum.computeResult();
    }

    /**
     * 对「已在别处（如密态）聚合完成的求和结果」加噪出库。
     * BoundedSum 需要逐条 addEntry，无法用于密文域求好的和，
     * 故直接用同源的 LaplaceNoise（L1 敏感度 = 单条记录贡献上界 clampUpper）。
     */
    public double noisedPreAggregatedSum(String dataset, long trueSum, double epsilon, long clampUpper) {
        ledger.consume(dataset, epsilon);
        return new LaplaceNoise().addNoise(trueSum, clampUpper, epsilon, null);
    }

    public double noisedMean(String dataset, long[] values, double epsilon, long clampLower, long clampUpper) {
        ledger.consume(dataset, epsilon);
        BoundedMean mean = BoundedMean.builder()
                .epsilon(epsilon)
                .lower(clampLower)
                .upper(clampUpper)
                .maxPartitionsContributed(1)
                .maxContributionsPerPartition(1)
                .build();
        for (long v : values) {
            mean.addEntry(v);
        }
        return mean.computeResult();
    }
}
