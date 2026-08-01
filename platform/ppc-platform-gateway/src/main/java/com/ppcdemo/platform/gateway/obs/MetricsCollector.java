package com.ppcdemo.platform.gateway.obs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 轻量指标采集（M5-P3 §5.P3 可观测）：按 (指标, 能力) 维度计数与延迟累计，
 * Prometheus 文本格式导出。零依赖、线程安全。规模上来可换 Micrometer，接口稳定。
 */
public final class MetricsCollector {

    /** 无操作实例：未启用可观测时网关默认引用，零开销。 */
    public static final MetricsCollector NOOP = new MetricsCollector(false);

    private final boolean enabled;
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> latencySum = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> latencyCount = new ConcurrentHashMap<>();

    public MetricsCollector() {
        this(true);
    }

    private MetricsCollector(boolean enabled) {
        this.enabled = enabled;
    }

    public void increment(String metric, String capability) {
        if (enabled) {
            counters.computeIfAbsent(key(metric, capability), k -> new LongAdder()).increment();
        }
    }

    public void recordLatency(String capability, long millis) {
        if (enabled) {
            latencySum.computeIfAbsent(capability, k -> new LongAdder()).add(millis);
            latencyCount.computeIfAbsent(capability, k -> new LongAdder()).increment();
        }
    }

    public long count(String metric, String capability) {
        LongAdder a = counters.get(key(metric, capability));
        return a == null ? 0 : a.sum();
    }

    /** Prometheus 文本导出（/pps/v1/metrics）。 */
    public String prometheus() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((k, v) -> {
            String[] parts = k.split("\\|", 2);
            sb.append("pps_requests_total{metric=\"%s\",capability=\"%s\"} %d%n"
                    .formatted(parts[0], parts[1], v.sum()));
        });
        latencySum.forEach((cap, sum) -> {
            long n = latencyCount.getOrDefault(cap, new LongAdder()).sum();
            sb.append("pps_latency_millis_sum{capability=\"%s\"} %d%n".formatted(cap, sum.sum()));
            sb.append("pps_latency_millis_count{capability=\"%s\"} %d%n".formatted(cap, n));
        });
        return sb.toString();
    }

    private static String key(String metric, String capability) {
        return metric + "|" + capability;
    }
}
