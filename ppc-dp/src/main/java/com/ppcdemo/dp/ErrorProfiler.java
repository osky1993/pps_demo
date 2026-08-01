package com.ppcdemo.dp;

import java.util.Arrays;
import java.util.function.DoubleSupplier;

/**
 * 误差剖析：固定数据重复加噪 N 次，实测相对误差分位数（M1 设计文档 §5.2）。
 * 产出 ε → P50/P95/P99 误差矩阵，给业务方和合规方看「ε 到底意味着多少误差」。
 */
public final class ErrorProfiler {

    public record ErrorProfile(double p50, double p95, double p99) {
        @Override
        public String toString() {
            return "相对误差 P50=%.4f%% P95=%.4f%% P99=%.4f%%".formatted(p50 * 100, p95 * 100, p99 * 100);
        }
    }

    private ErrorProfiler() {
    }

    /**
     * @param trueValue    真值（非零）
     * @param noisedSample 每次调用产出一个新的加噪结果
     * @param repeat       重复次数（设计基线 1000）
     */
    public static ErrorProfile profile(double trueValue, DoubleSupplier noisedSample, int repeat) {
        if (trueValue == 0) {
            throw new IllegalArgumentException("真值为 0 无法计算相对误差");
        }
        double[] relativeErrors = new double[repeat];
        for (int i = 0; i < repeat; i++) {
            relativeErrors[i] = Math.abs(noisedSample.getAsDouble() - trueValue) / Math.abs(trueValue);
        }
        Arrays.sort(relativeErrors);
        return new ErrorProfile(
                percentile(relativeErrors, 0.50),
                percentile(relativeErrors, 0.95),
                percentile(relativeErrors, 0.99));
    }

    private static double percentile(double[] sorted, double q) {
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
