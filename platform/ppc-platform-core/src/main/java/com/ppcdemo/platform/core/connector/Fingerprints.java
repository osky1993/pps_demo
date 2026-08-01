package com.ppcdemo.platform.core.connector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * 数据集内容指纹（M2 §4.6）：行数 + 有序抽样 SHA-256。
 * 抽样：ID 升序前 1000 行（含金额），确定性且对全量替换/篡改敏感；
 * 行数变化必然改变指纹，抽样外单行篡改的检出是概率性的（设计权衡，审计手册注明）。
 */
public final class Fingerprints {

    private static final int SAMPLE = 1_000;

    private Fingerprints() {
    }

    public static String of(long[] ids, Map<Long, Long> amountCentsById) {
        long[] sorted = ids.clone();
        java.util.Arrays.sort(sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("rows=" + ids.length + ";").getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < Math.min(SAMPLE, sorted.length); i++) {
                String row = amountCentsById == null
                        ? String.valueOf(sorted[i])
                        : sorted[i] + ":" + amountCentsById.get(sorted[i]);
                digest.update((row + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return "v1:" + ids.length + ":" + HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
