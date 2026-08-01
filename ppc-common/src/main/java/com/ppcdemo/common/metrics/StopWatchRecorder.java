package com.ppcdemo.common.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段计时器：setup（离线准备）与 online（在线计算）等阶段分开记录，
 * 两段的优化空间完全不同（M1 设计文档 §3.4）。非线程安全，按进程内单实例使用。
 */
public final class StopWatchRecorder {

    private final Map<String, Long> startNanos = new LinkedHashMap<>();
    private final Map<String, Long> elapsedNanos = new LinkedHashMap<>();

    public void start(String phase) {
        if (startNanos.containsKey(phase)) {
            throw new IllegalStateException("阶段已在计时：" + phase);
        }
        startNanos.put(phase, System.nanoTime());
    }

    public void stop(String phase) {
        Long begin = startNanos.remove(phase);
        if (begin == null) {
            throw new IllegalStateException("阶段未开始计时：" + phase);
        }
        elapsedNanos.merge(phase, System.nanoTime() - begin, Long::sum);
    }

    public long elapsedMillis(String phase) {
        Long nanos = elapsedNanos.get(phase);
        if (nanos == null) {
            throw new IllegalStateException("阶段无计时数据：" + phase);
        }
        return nanos / 1_000_000;
    }

    /** 阶段名 → 毫秒，保持记录顺序。 */
    public Map<String, Long> allMillis() {
        Map<String, Long> out = new LinkedHashMap<>();
        elapsedNanos.forEach((k, v) -> out.put(k, v / 1_000_000));
        return out;
    }
}
