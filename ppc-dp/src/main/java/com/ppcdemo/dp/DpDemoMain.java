package com.ppcdemo.dp;

import com.google.privacy.differentialprivacy.LaplaceNoise;
import com.ppcdemo.common.config.PocConfig;
import com.ppcdemo.common.datagen.ValueGenerator;
import com.ppcdemo.common.metrics.CsvReporter;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * PoC-3 演示入口：ε ∈ {0.1, 1, 10} 的误差分位数矩阵 + 预算记账熔断演示。
 * 用法：java ... DpDemoMain [poc.yaml 路径]
 *
 * 剖析方法论：count/sum 的 DP 原语 = 确定性聚合 + 拉普拉斯噪声，
 * 因此聚合只算一次、噪声重复采样 1000 次（LaplaceNoise 与 Count/BoundedSum 内部同源，
 * 数学等价且省去 10^9 量级的无谓重复聚合）；mean 的误差结构是「噪声和/噪声数」之商，
 * 无法这样分解，用真实 BoundedMean 以小样本（10 万 × 100 次）实测。
 */
public final class DpDemoMain {

    private static final double[] EPSILONS = {0.1, 1.0, 10.0};
    private static final int REPEAT = 1_000;
    private static final int MEAN_REPEAT = 100;
    private static final int MEAN_SAMPLE = 100_000;

    private DpDemoMain() {
    }

    public static void main(String[] args) {
        Path yaml = Path.of(args.length > 0 ? args[0] : "poc.yaml");
        PocConfig config = PocConfig.load(yaml);
        CsvReporter reporter = new CsvReporter(Path.of(config.bench().outputCsv()));

        int n = 1_000_000;
        long clampLower = config.dp().clampLower();
        long clampUpper = config.dp().clampUpper();
        long[] values = ValueGenerator.logNormalCents(config.dataset().seed(), n, clampUpper);
        long trueSum = Arrays.stream(values).sum();
        long[] meanSample = Arrays.copyOf(values, MEAN_SAMPLE);
        double trueMeanOfSample = (double) Arrays.stream(meanSample).sum() / MEAN_SAMPLE;

        System.out.printf("DP 误差矩阵（n=%d, clamp=[%d,%d]）%n", n, clampLower, clampUpper);
        System.out.printf("真值：count=%d, sum=%d 分%n%n", n, trueSum);

        LaplaceNoise noise = new LaplaceNoise();
        for (double epsilon : EPSILONS) {
            var countProfile = ErrorProfiler.profile(n,
                    () -> noise.addNoise((long) n, 1L, epsilon, null), REPEAT);
            var sumProfile = ErrorProfiler.profile(trueSum,
                    () -> noise.addNoise(trueSum, clampUpper, epsilon, null), REPEAT);

            BudgetLedger meanLedger = new BudgetLedger(epsilon * MEAN_REPEAT + 1);
            DpAggregator dp = new DpAggregator(meanLedger);
            var meanProfile = ErrorProfiler.profile(trueMeanOfSample,
                    () -> dp.noisedMean("profiling", meanSample, epsilon, clampLower, clampUpper),
                    MEAN_REPEAT);

            System.out.printf("ε=%-4s | count(n=%d)      %s%n", epsilon, n, countProfile);
            System.out.printf("       | sum(n=%d)        %s%n", n, sumProfile);
            System.out.printf("       | mean(n=%d,rep=%d) %s%n%n", MEAN_SAMPLE, MEAN_REPEAT, meanProfile);
            report(reporter, "count", epsilon, countProfile);
            report(reporter, "sum", epsilon, sumProfile);
            report(reporter, "mean", epsilon, meanProfile);
        }

        System.out.printf("预算记账演示（budgetTotal=%.1f, 每次查询 ε=%.1f）：%n",
                config.dp().budgetTotal(), config.dp().epsilon());
        BudgetLedger ledger = new BudgetLedger(config.dp().budgetTotal());
        DpAggregator gateway = new DpAggregator(ledger);
        int served = 0;
        try {
            while (true) {
                gateway.noisedCount("交易数据集", n, config.dp().epsilon());
                served++;
            }
        } catch (BudgetLedger.BudgetExhaustedException e) {
            System.out.printf("  成功出库 %d 次后熔断：%s%n", served, e.getMessage());
        }
    }

    private static void report(CsvReporter reporter, String metric, double epsilon,
                               ErrorProfiler.ErrorProfile profile) {
        String dataset = "1000000条";
        reporter.report("dp", dataset, "local", "epsilon=" + epsilon, metric + "_p50", profile.p50(), "相对误差");
        reporter.report("dp", dataset, "local", "epsilon=" + epsilon, metric + "_p95", profile.p95(), "相对误差");
        reporter.report("dp", dataset, "local", "epsilon=" + epsilon, metric + "_p99", profile.p99(), "相对误差");
    }
}
