package com.ppcdemo.bench;

import com.ppcdemo.common.metrics.CsvReporter;
import com.ppcdemo.pir.LocalPirRunner;
import com.ppcdemo.pir.PirSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PIR 基准（M4）：{pai_cks, simple_naive} × {1万, 10万, 100万} 库规模。
 * 客户端预处理 PIR 的成本结构特点：init 随库规模增长、在线查询极廉——分开计量以体现该权衡。
 *
 * 用法：java ... PirBench [输出CSV] [最大库规模]
 */
public final class PirBench {

    private static final int VALUE_BITS = 128;
    private static final int VALUE_BYTES = VALUE_BITS / 8;
    private static final int QUERY_NUM = 10;

    private PirBench() {
    }

    public static void main(String[] args) throws Exception {
        CsvReporter reporter = new CsvReporter(Path.of(args.length > 0 ? args[0] : "bench-pir.csv"));
        int maxSize = args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000;
        int[] sizes = {10_000, 100_000, 1_000_000};
        String[] protocols = {"pai_cks", "simple_naive"};

        System.out.printf("%-14s %10s %10s %12s %12s%n",
                "协议", "库规模", "init(ms)", "查询10条(ms)", "通信(MB)");
        for (int n : sizes) {
            if (n > maxSize) {
                continue;
            }
            Map<String, byte[]> db = buildDatabase(n);
            List<String> queries = buildQueries(n);
            for (String protocol : protocols) {
                var result = LocalPirRunner.run(protocol, db, VALUE_BITS, queries);
                verify(db, queries, result.values());
                double mb = (result.serverSentBytes() + result.clientSentBytes()) / 1024.0 / 1024;
                System.out.printf("%-14s %10d %10d %12d %12.2f%n",
                        protocol, n, result.initMillis(), result.queryMillis(), mb);
                reporter.report("pir-" + protocol, n + "条", "loopback",
                        "init", "elapsed", result.initMillis(), "ms");
                reporter.report("pir-" + protocol, n + "条", "loopback",
                        "online", "elapsed", result.queryMillis(), "ms");
                reporter.report("pir-" + protocol, n + "条", "loopback",
                        "online", "trafficTotal", mb, "MB");
            }
        }
        System.out.println("完成。init 为一次性成本（一次建库多次查询摊薄），online 为单批查询成本。");
    }

    private static Map<String, byte[]> buildDatabase(int n) {
        Map<String, byte[]> db = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            db.put("user-" + i, PirSupport.fitValue(
                    ("score-" + (600 + i % 300)).getBytes(StandardCharsets.UTF_8), VALUE_BYTES));
        }
        return db;
    }

    private static List<String> buildQueries(int n) {
        List<String> queries = new ArrayList<>(QUERY_NUM);
        for (int i = 0; i < QUERY_NUM; i++) {
            queries.add("user-" + (i * (n / QUERY_NUM)));
        }
        return queries;
    }

    private static void verify(Map<String, byte[]> db, List<String> queries, byte[][] values) {
        for (int i = 0; i < queries.size(); i++) {
            if (!java.util.Arrays.equals(db.get(queries.get(i)), values[i])) {
                throw new IllegalStateException("查询结果与库不一致：" + queries.get(i));
            }
        }
    }
}
