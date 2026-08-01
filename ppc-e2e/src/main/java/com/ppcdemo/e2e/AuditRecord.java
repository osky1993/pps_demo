package com.ppcdemo.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务级审计记录（总体设计 §3.4 审计要求的字段雏形，M2 审计中心 schema 起点）。
 * PoC 手写 JSON，不引依赖。
 */
public final class AuditRecord {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public AuditRecord(String taskId, String taskType) {
        fields.put("taskId", taskId);
        fields.put("taskType", taskType);
        fields.put("timestamp", Instant.now().toString());
    }

    public AuditRecord put(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public String toJson() {
        return fields.entrySet().stream()
                .map(e -> "  \"%s\": %s".formatted(e.getKey(), render(e.getValue())))
                .collect(Collectors.joining(",\n", "{\n", "\n}"));
    }

    public void writeTo(Path path) {
        try {
            Files.writeString(path, toJson() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("审计记录写入失败：" + path, e);
        }
    }

    private static String render(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + value.toString().replace("\"", "\\\"") + "\"";
    }
}
