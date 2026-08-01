package com.ppcdemo.common.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * poc.yaml 的类型化加载（结构见 M1 设计文档附录 A）。
 * 显式手工映射，不用反射绑定 —— 配置结构小且稳定，宁可啰嗦不要魔法。
 */
public final class PocConfig {

    public record Party(String role, String local, String peer) {
    }

    public record Dataset(String profile, long seed, double intersectionRatio) {
        /** S/M/L 档位对应规模（M1 设计文档 §2.3）。 */
        public int size() {
            return switch (profile) {
                case "S" -> 10_000;
                case "M" -> 1_000_000;
                case "L" -> 10_000_000;
                default -> throw new IllegalArgumentException("未知数据档位：" + profile);
            };
        }
    }

    public record Psi(String protocol) {
    }

    public record Phe(int keyBits, int parallelism) {
    }

    public record Dp(double epsilon, long clampLower, long clampUpper, double budgetTotal) {
    }

    public record Bench(int repeat, String outputCsv) {
    }

    private final Party party;
    private final Dataset dataset;
    private final Psi psi;
    private final Phe phe;
    private final Dp dp;
    private final Bench bench;

    private PocConfig(Party party, Dataset dataset, Psi psi, Phe phe, Dp dp, Bench bench) {
        this.party = party;
        this.dataset = dataset;
        this.psi = psi;
        this.phe = phe;
        this.dp = dp;
        this.bench = bench;
    }

    public static PocConfig load(Path yamlPath) {
        try (InputStream in = Files.newInputStream(yamlPath)) {
            return parse(new Yaml().load(in));
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置失败：" + yamlPath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static PocConfig parse(Map<String, Object> root) {
        Map<String, Object> party = (Map<String, Object>) root.getOrDefault("party", Map.of());
        Map<String, Object> dataset = (Map<String, Object>) root.getOrDefault("dataset", Map.of());
        Map<String, Object> psi = (Map<String, Object>) root.getOrDefault("psi", Map.of());
        Map<String, Object> phe = (Map<String, Object>) root.getOrDefault("phe", Map.of());
        Map<String, Object> dp = (Map<String, Object>) root.getOrDefault("dp", Map.of());
        Map<String, Object> bench = (Map<String, Object>) root.getOrDefault("bench", Map.of());
        return new PocConfig(
                new Party(str(party, "role", "server"), str(party, "local", "0.0.0.0:9001"),
                        str(party, "peer", "127.0.0.1:9002")),
                new Dataset(str(dataset, "profile", "S"), longVal(dataset, "seed", 20260801L),
                        doubleVal(dataset, "intersectionRatio", 0.5)),
                new Psi(str(psi, "protocol", "rr22")),
                new Phe(intVal(phe, "keyBits", 2048), intVal(phe, "parallelism",
                        Runtime.getRuntime().availableProcessors())),
                new Dp(doubleVal(dp, "epsilon", 1.0), longVal(dp, "clampLower", 0L),
                        longVal(dp, "clampUpper", 100_000L), doubleVal(dp, "budgetTotal", 10.0)),
                new Bench(intVal(bench, "repeat", 5), str(bench, "outputCsv", "./bench-result.csv")));
    }

    private static String str(Map<String, Object> m, String key, String dft) {
        Object v = m.get(key);
        return v == null ? dft : v.toString();
    }

    private static int intVal(Map<String, Object> m, String key, int dft) {
        Object v = m.get(key);
        return v == null ? dft : ((Number) v).intValue();
    }

    private static long longVal(Map<String, Object> m, String key, long dft) {
        Object v = m.get(key);
        return v == null ? dft : ((Number) v).longValue();
    }

    private static double doubleVal(Map<String, Object> m, String key, double dft) {
        Object v = m.get(key);
        return v == null ? dft : ((Number) v).doubleValue();
    }

    public Party party() {
        return party;
    }

    public Dataset dataset() {
        return dataset;
    }

    public Psi psi() {
        return psi;
    }

    public Phe phe() {
        return phe;
    }

    public Dp dp() {
        return dp;
    }

    public Bench bench() {
        return bench;
    }
}
