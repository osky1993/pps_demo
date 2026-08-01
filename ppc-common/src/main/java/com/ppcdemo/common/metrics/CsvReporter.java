package com.ppcdemo.common.metrics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 基准数据统一出口。报告图表一律由脚本从本 CSV 生成，禁止手抄数据（M1 设计文档 §7.2）。
 * 格式：scenario,dataset,network,phase,metric,value,unit,timestamp
 */
public final class CsvReporter {

    private static final String HEADER = "scenario,dataset,network,phase,metric,value,unit,timestamp";

    private final Path csvPath;

    public CsvReporter(Path csvPath) {
        this.csvPath = csvPath;
    }

    public void report(String scenario, String dataset, String network,
                       String phase, String metric, double value, String unit) {
        String line = String.join(",",
                scenario, dataset, network, phase, metric,
                String.valueOf(value), unit, Instant.now().toString());
        try {
            if (Files.notExists(csvPath)) {
                if (csvPath.getParent() != null) {
                    Files.createDirectories(csvPath.getParent());
                }
                Files.writeString(csvPath, HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
            Files.writeString(csvPath, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写入基准 CSV 失败：" + csvPath, e);
        }
    }
}
