package com.ppcdemo.common.datagen;

import java.util.SplittableRandom;

/**
 * 生成与 ID 关联的金额列。分布取对数正态（贴近真实消费金额形态）。
 * 平台编码规范雏形：金额一律以「分」为单位的 long 表示（Paillier 只对整数运算，
 * 金额 ×100 转整数入密文，解密后还原 —— M1 设计文档 §4.2）。
 */
public final class ValueGenerator {

    private ValueGenerator() {
    }

    /**
     * @param seed       随机种子
     * @param n          条数
     * @param clampUpper 单条金额上界（分），与 DP 的 clamp 上界保持同一语义
     */
    public static long[] logNormalCents(long seed, int n, long clampUpper) {
        SplittableRandom random = new SplittableRandom(seed);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            // exp(N(mu=8, sigma=1.2)) ≈ 中位数 3000 分（30 元），长尾到数十万分
            double v = Math.exp(8 + 1.2 * nextGaussian(random));
            out[i] = Math.max(1, Math.min(clampUpper, (long) v));
        }
        return out;
    }

    /** SplittableRandom 无 nextGaussian（JDK17 才有 RandomGenerator 接口版），Box-Muller 自给。 */
    private static double nextGaussian(SplittableRandom random) {
        double u1 = random.nextDouble();
        double u2 = random.nextDouble();
        return Math.sqrt(-2 * Math.log(u1 + Double.MIN_VALUE)) * Math.cos(2 * Math.PI * u2);
    }
}
