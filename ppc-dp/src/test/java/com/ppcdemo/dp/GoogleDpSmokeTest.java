package com.ppcdemo.dp;

import com.google.privacy.differentialprivacy.BoundedSum;
import com.google.privacy.differentialprivacy.Count;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1 冒烟：验证 Google DP Java 库（differentialprivacy 4.0.0）在 JDK 17+ 上可用。
 * 加噪结果落在宽松界内即可 —— 严格的误差分布验证是 W3 ErrorProfiler 的事。
 */
class GoogleDpSmokeTest {

    @Test
    void 加噪count在宽松界内() {
        Count count = Count.builder()
                .epsilon(1.0)
                .maxPartitionsContributed(1)
                .build();
        count.incrementBy(1_000_000);
        double noised = count.computeResult();
        // ε=1 的拉普拉斯噪声 P(|noise|>100) 约 e^-100，界给到 ±10000 纯为防环境异常
        assertTrue(Math.abs(noised - 1_000_000) < 10_000,
                "加噪后偏差异常：" + noised);
    }

    @Test
    void 加噪boundedSum在宽松界内() {
        BoundedSum sum = BoundedSum.builder()
                .epsilon(1.0)
                .lower(0)
                .upper(100_000)
                .maxPartitionsContributed(1)
                .build();
        for (int i = 0; i < 1_000; i++) {
            sum.addEntry(3_000);
        }
        double noised = sum.computeResult();
        // 敏感度 = upper = 1e5，ε=1 时噪声标准差 ~1.4e5，给 100 倍余量
        assertTrue(Math.abs(noised - 3_000_000) < 10_000_000,
                "加噪后偏差异常：" + noised);
    }
}
