package com.ppcdemo.common.datagen;

import java.util.HashSet;
import java.util.SplittableRandom;

/**
 * 生成两方 ID 集合（64bit ID），交集为构造出的已知集合，用于协议输出的真值校验。
 * 固定 seed 保证可复现（M1 设计文档 §2.3）。
 */
public final class IdSetGenerator {

    /** 两方数据集：交集部分在 partyA/partyB 中均出现，真值即 intersection。 */
    public record TwoPartySets(long[] partyA, long[] partyB, long[] intersection) {
    }

    private IdSetGenerator() {
    }

    /**
     * @param seed              随机种子（可复现）
     * @param sizeA             A 方集合规模
     * @param sizeB             B 方集合规模
     * @param intersectionRatio 交集规模 = ratio * min(sizeA, sizeB)
     */
    public static TwoPartySets generate(long seed, int sizeA, int sizeB, double intersectionRatio) {
        if (intersectionRatio < 0 || intersectionRatio > 1) {
            throw new IllegalArgumentException("intersectionRatio 必须在 [0,1]：" + intersectionRatio);
        }
        int common = (int) (Math.min(sizeA, sizeB) * intersectionRatio);
        SplittableRandom random = new SplittableRandom(seed);
        HashSet<Long> used = new HashSet<>(sizeA + sizeB);

        long[] intersection = distinct(random, used, common);
        long[] aOnly = distinct(random, used, sizeA - common);
        long[] bOnly = distinct(random, used, sizeB - common);

        long[] partyA = concatShuffled(intersection, aOnly, random.split());
        long[] partyB = concatShuffled(intersection, bOnly, random.split());
        return new TwoPartySets(partyA, partyB, intersection);
    }

    private static long[] distinct(SplittableRandom random, HashSet<Long> used, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            long v;
            do {
                v = random.nextLong();
            } while (!used.add(v));
            out[i] = v;
        }
        return out;
    }

    private static long[] concatShuffled(long[] x, long[] y, SplittableRandom random) {
        long[] out = new long[x.length + y.length];
        System.arraycopy(x, 0, out, 0, x.length);
        System.arraycopy(y, 0, out, x.length, y.length);
        for (int i = out.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return out;
    }
}
