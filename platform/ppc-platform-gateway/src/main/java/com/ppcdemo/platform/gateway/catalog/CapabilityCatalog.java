package com.ppcdemo.platform.gateway.catalog;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 能力目录（M5 §4）：配置驱动的能力注册表。
 * 由 PPS 管理员 + 双方合规人员共同定义（非业务系统自定义）；P3 增删改走双方审批。
 */
public final class CapabilityCatalog {

    private final Map<String, Capability> byId = new LinkedHashMap<>();

    private CapabilityCatalog() {
    }

    public static CapabilityCatalog fromYaml(Path yamlFile) {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            return parse(new Yaml().load(in));
        } catch (IOException e) {
            throw new UncheckedIOException("读取能力目录失败：" + yamlFile, e);
        }
    }

    public static CapabilityCatalog fromResource(String resourcePath) {
        try (InputStream in = CapabilityCatalog.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("能力目录资源不存在：" + resourcePath);
            }
            return parse(new Yaml().load(in));
        } catch (IOException e) {
            throw new UncheckedIOException("读取能力目录资源失败：" + resourcePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static CapabilityCatalog parse(Map<String, Object> root) {
        CapabilityCatalog catalog = new CapabilityCatalog();
        List<Map<String, Object>> caps =
                (List<Map<String, Object>>) root.getOrDefault("capabilities", List.of());
        for (Map<String, Object> c : caps) {
            Map<String, Object> quota = (Map<String, Object>) c.getOrDefault("quota", Map.of());
            Capability capability = new Capability(
                    str(c, "id"), str(c, "displayName"),
                    Capability.Mode.valueOf(str(c, "mode")),
                    str(c, "taskType"), str(c, "protocol"), str(c, "partner"),
                    str(c, "dataset"), str(c, "outputGranularity"),
                    intVal(c, "valueBits", 128),
                    longVal(quota, "datasetDaily", 100_000),
                    longVal(quota, "perAppDaily", 10_000));
            catalog.byId.put(capability.id(), capability);
        }
        return catalog;
    }

    public Optional<Capability> find(String capabilityId) {
        return Optional.ofNullable(byId.get(capabilityId));
    }

    public Capability require(String capabilityId) {
        return find(capabilityId).orElseThrow(
                () -> new IllegalArgumentException("未知能力：" + capabilityId));
    }

    public List<Capability> all() {
        return List.copyOf(byId.values());
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static int intVal(Map<String, Object> m, String key, int dft) {
        Object v = m.get(key);
        return v == null ? dft : ((Number) v).intValue();
    }

    private static long longVal(Map<String, Object> m, String key, long dft) {
        Object v = m.get(key);
        return v == null ? dft : ((Number) v).longValue();
    }
}
