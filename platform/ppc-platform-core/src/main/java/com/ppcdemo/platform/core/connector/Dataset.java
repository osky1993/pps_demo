package com.ppcdemo.platform.core.connector;

import java.util.Map;

/**
 * 接入后的数据集：ID 列 + 可选金额列（分）+ 内容指纹。
 * 金额编码规范（平台级）：外部数据以「元」计的十进制值 ×100 转 long「分」。
 */
public record Dataset(long[] ids, Map<Long, Long> amountCentsById, String fingerprint) {

    public static final class FingerprintMismatchException extends RuntimeException {
        public FingerprintMismatchException(String expected, String actual) {
            super("数据集内容指纹不符（审批后数据可能被替换）：契约=%s 实际=%s"
                    .formatted(expected, actual));
        }
    }

    /** 执行前指纹复核（M2 §4.6：防审批后换数据）。契约未登记指纹则跳过（S4 前的旧任务）。 */
    public void requireFingerprint(String expected) {
        if (expected != null && !expected.equals(fingerprint)) {
            throw new FingerprintMismatchException(expected, fingerprint);
        }
    }
}
