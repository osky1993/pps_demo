package com.ppcdemo.platform.fate;

/**
 * 联邦训练规格（M3 §4.1）：契约之外的算法侧参数，模板化生成 FATE conf（杜绝自由 JSON 注入）。
 * 一期算法：HETERO_LR。standalone 开发态 guest=9999 / host=10000。
 */
public record TrainingSpec(
        Algorithm algorithm,
        int guestPartyId,
        int hostPartyId,
        String namespace,
        String guestTable,
        String hostTable,
        int maxIter,
        double alpha,
        double learningRate,
        int batchSize) {

    public enum Algorithm { HETERO_LR }

    public static TrainingSpec heteroLrDefaults(String namespace, String guestTable, String hostTable) {
        return new TrainingSpec(Algorithm.HETERO_LR, 9999, 10000,
                namespace, guestTable, hostTable, 30, 0.01, 0.15, 320);
    }

    public TrainingSpec {
        if (maxIter <= 0 || maxIter > 1000) {
            throw new IllegalArgumentException("maxIter 超出白名单范围 (0,1000]：" + maxIter);
        }
        if (batchSize < -1 || batchSize == 0) {
            throw new IllegalArgumentException("batchSize 非法：" + batchSize);
        }
        for (String s : new String[]{namespace, guestTable, hostTable}) {
            if (s == null || !s.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("表名/命名空间仅允许字母数字下划线：" + s);
            }
        }
    }
}
