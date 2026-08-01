package com.ppcdemo.engine.psi;

import com.ppcdemo.pir.NettyPirRunner;
import com.ppcdemo.pir.PirSupport;
import com.ppcdemo.psi.NettyPsiRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * PIR 协议族处理（M4）：引擎进程按 job 的 protocolFamily 分派到此。
 *
 * job 字段：
 *   server 侧 idsFile = 键值库 CSV（key,value 两列）
 *   client 侧 idsFile = 查询键（每行一个），outputFile 写结果（与查询顺序对齐，未命中写空行）
 *   valueBits = value 比特长度（契约声明）
 */
public final class PirEngineHandler {

    private PirEngineHandler() {
    }

    public static void handle(Properties job, String role, String protocol,
                              NettyPsiRunner.Endpoint local, NettyPsiRunner.Endpoint peer)
            throws Exception {
        int valueBits = Integer.parseInt(job.getProperty("valueBits",
                String.valueOf(PirSupport.DEFAULT_VALUE_BIT_LENGTH)));
        int peerSize = Integer.parseInt(job.getProperty("peerSize", "1"));
        Path dataFile = Path.of(job.getProperty("idsFile"));

        if ("server".equals(role)) {
            Map<String, byte[]> db = readDatabase(dataFile, valueBits / 8);
            var result = NettyPirRunner.runServer(protocol, db, valueBits, peerSize, local, peer);
            System.out.printf("engine[pir-server] done: %d ms, |DB|=%d, sent %d B%n",
                    result.elapsedMillis(), db.size(), result.sentBytes());
            return;
        }

        List<String> queries;
        try (var lines = Files.lines(dataFile)) {
            queries = lines.filter(l -> !l.isBlank()).toList();
        }
        // 库规模由契约声明（查询方本就应知道库有多大，这不是秘密）
        int databaseSize = Integer.parseInt(job.getProperty("databaseSize",
                String.valueOf(peerSize)));
        var result = NettyPirRunner.runClient(protocol, databaseSize, valueBits, queries, local, peer);

        List<String> texts = new ArrayList<>(queries.size());
        int hits = 0;
        for (byte[] value : result.values()) {
            String text = value == null ? "" : PirSupport.decodeText(value);
            texts.add(text);
            if (!text.isBlank()) {
                hits++;
            }
        }
        Files.writeString(Path.of(job.getProperty("outputFile")),
                String.join("\n", texts) + "\n", StandardCharsets.UTF_8);
        System.out.printf("engine[pir-client] done: %d ms, |Q|=%d, hits=%d, sent %d B%n",
                result.elapsedMillis(), queries.size(), hits, result.sentBytes());
    }

    /** 键值库 CSV：key,value（value 为文本，按契约长度定长化）。 */
    private static Map<String, byte[]> readDatabase(Path file, int valueBytes) throws IOException {
        Map<String, byte[]> db = new LinkedHashMap<>();
        try (var lines = Files.lines(file)) {
            lines.filter(l -> !l.isBlank()).forEach(line -> {
                int comma = line.indexOf(',');
                if (comma < 0) {
                    throw new IllegalArgumentException("键值库行格式应为 key,value：" + line);
                }
                db.put(line.substring(0, comma).trim(),
                        PirSupport.fitValue(line.substring(comma + 1).trim()
                                .getBytes(StandardCharsets.UTF_8), valueBytes));
            });
        }
        return db;
    }
}
