package com.ppcdemo.platform.gateway.catalog;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Capability ↔ properties 文本序列化（持久化与变更审批用，零 JSON 依赖）。 */
public final class CapabilitySerde {

    private CapabilitySerde() {
    }

    public static String serialize(Capability c) {
        Properties p = new Properties();
        p.setProperty("id", c.id());
        p.setProperty("displayName", nz(c.displayName()));
        p.setProperty("mode", c.mode().name());
        p.setProperty("taskType", nz(c.taskType()));
        p.setProperty("protocol", nz(c.protocol()));
        p.setProperty("partner", nz(c.partner()));
        p.setProperty("dataset", nz(c.dataset()));
        p.setProperty("outputGranularity", nz(c.outputGranularity()));
        p.setProperty("valueBits", String.valueOf(c.valueBits()));
        p.setProperty("datasetDailyQuota", String.valueOf(c.datasetDailyQuota()));
        p.setProperty("perAppDailyQuota", String.valueOf(c.perAppDailyQuota()));
        p.setProperty("maxConcurrency", String.valueOf(c.maxConcurrency()));
        try (StringWriter w = new StringWriter()) {
            p.store(w, "ppc capability");
            return w.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Capability deserialize(String spec) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(spec));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Capability(p.getProperty("id"), p.getProperty("displayName"),
                Capability.Mode.valueOf(p.getProperty("mode")),
                p.getProperty("taskType"), p.getProperty("protocol"), p.getProperty("partner"),
                p.getProperty("dataset"), p.getProperty("outputGranularity"),
                Integer.parseInt(p.getProperty("valueBits", "128")),
                Long.parseLong(p.getProperty("datasetDailyQuota", "100000")),
                Long.parseLong(p.getProperty("perAppDailyQuota", "10000")),
                Integer.parseInt(p.getProperty("maxConcurrency", "4")));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
