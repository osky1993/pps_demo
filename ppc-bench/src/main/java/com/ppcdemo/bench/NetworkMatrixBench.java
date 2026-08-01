package com.ppcdemo.bench;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.common.metrics.CsvReporter;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.psi.SubprocessPsiEngineRunner;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络矩阵基准（M2.5 #5）：{rr22, kkrt16} × {S, M} × {direct, wan}，
 * wan = ShapedRelay 注入 10ms 单向时延（RTT 20ms）+ 50Mbps 带宽上限（对齐原 netem 口径）。
 * 纪律：S 档 5 次、M 档 3 次取中位数（M 档偏离 5 次纪律在报告注明）；每次校验交集基数。
 *
 * 用法：java ... NetworkMatrixBench [输出CSV路径]
 */
public final class NetworkMatrixBench {

    private record Case(String protocol, String profile, int size, double ratio, boolean wan, int reps) {
    }

    private static final Pattern DONE = Pattern.compile("done: (\\d+) ms, \\|I\\|=(\\d+), sent (\\d+) B");

    private NetworkMatrixBench() {
    }

    public static void main(String[] args) throws Exception {
        CsvReporter reporter = new CsvReporter(Path.of(args.length > 0 ? args[0] : "bench-network-matrix.csv"));
        List<Case> cases = List.of(
                new Case("rr22", "S", 10_000, 0.5, false, 5),
                new Case("rr22", "S", 10_000, 0.5, true, 5),
                new Case("kkrt16", "S", 10_000, 0.5, false, 5),
                new Case("kkrt16", "S", 10_000, 0.5, true, 5),
                new Case("rr22", "M", 1_000_000, 0.3, false, 3),
                new Case("rr22", "M", 1_000_000, 0.3, true, 3),
                new Case("kkrt16", "M", 1_000_000, 0.3, false, 3),
                new Case("kkrt16", "M", 1_000_000, 0.3, true, 3));

        SubprocessPsiEngineRunner runner = SubprocessPsiEngineRunner.currentJvm(600);
        System.out.printf("%-8s %-4s %-8s %8s %10s %8s%n", "协议", "档", "网络", "中位ms", "min-max", "MB");
        for (Case c : cases) {
            var sets = IdSetGenerator.generate(20260801L, c.size(), c.size(), c.ratio());
            long expected = sets.intersection().length;
            List<Long> elapsed = new ArrayList<>();
            long sentBytes = 0;
            for (int rep = 0; rep < c.reps(); rep++) {
                Result r = runOnce(runner, c, sets.partyA(), sets.partyB(), expected);
                elapsed.add(r.clientMillis());
                sentBytes = r.totalSentBytes();
            }
            elapsed.sort(Long::compare);
            long median = elapsed.get(elapsed.size() / 2);
            String network = c.wan() ? "wan-sim" : "direct";
            System.out.printf("%-8s %-4s %-8s %8d %5d-%-5d %8.1f%n", c.protocol(), c.profile(),
                    network, median, elapsed.get(0), elapsed.get(elapsed.size() - 1),
                    sentBytes / 1024.0 / 1024);
            reporter.report("psi-" + c.protocol(), c.profile() + "档", network,
                    "online", "elapsedMedian", median, "ms");
            reporter.report("psi-" + c.protocol(), c.profile() + "档", network,
                    "online", "trafficTotal", sentBytes / 1024, "KB");
        }
        System.out.println("完成。整形口径：单向 10ms（RTT 20ms）+ 50Mbps；真机矩阵按 M2 附录 A 口径复测。");
    }

    private record Result(long clientMillis, long totalSentBytes) {
    }

    private static Result runOnce(SubprocessPsiEngineRunner runner, Case c,
                                  long[] serverIds, long[] clientIds, long expected) throws Exception {
        int serverPort = freePort();
        int clientPort = freePort();
        ShapedRelay toServer = null;
        ShapedRelay toClient = null;
        try {
            int serverTarget = serverPort;
            int clientTarget = clientPort;
            if (c.wan()) {
                toServer = new ShapedRelay(0, serverPort, 10, 50_000_000L);
                toClient = new ShapedRelay(0, clientPort, 10, 50_000_000L);
                serverTarget = toServer.port();   // client 拨它 → server
                clientTarget = toClient.port();   // server 拨它 → client
            }
            Path idsA = writeIds(serverIds);
            Path idsB = writeIds(clientIds);
            Path output = Files.createTempFile("matrix-out", ".txt");
            var serverJob = new EngineRunner.EngineJob("server", c.protocol(),
                    "127.0.0.1:" + serverPort, "127.0.0.1:" + clientTarget, idsA, null,
                    serverIds.length, clientIds.length);
            var clientJob = new EngineRunner.EngineJob("client", c.protocol(),
                    "127.0.0.1:" + clientPort, "127.0.0.1:" + serverTarget, idsB, output,
                    clientIds.length, serverIds.length);

            CompletableFuture<EngineRunner.EngineResult> serverFuture =
                    CompletableFuture.supplyAsync(() -> runner.run(serverJob));
            Thread.sleep(500);   // server 先行（真实运维顺序）
            EngineRunner.EngineResult clientResult = runner.run(clientJob);
            EngineRunner.EngineResult serverResult = serverFuture.join();
            if (!clientResult.success() || !serverResult.success()) {
                throw new IllegalStateException("引擎失败：client=%d server=%d%n%s"
                        .formatted(clientResult.exitCode(), serverResult.exitCode(), clientResult.log()));
            }
            Matcher m = DONE.matcher(clientResult.log());
            if (!m.find()) {
                throw new IllegalStateException("client 日志无 done 行：" + clientResult.log());
            }
            long clientMillis = Long.parseLong(m.group(1));
            if (Long.parseLong(m.group(2)) != expected) {
                throw new IllegalStateException("交集基数不符：" + m.group(2) + " != " + expected);
            }
            long clientSent = Long.parseLong(m.group(3));
            Matcher sm = Pattern.compile("sent (\\d+) B").matcher(serverResult.log());
            long serverSent = sm.find() ? Long.parseLong(sm.group(1)) : 0;
            Files.deleteIfExists(idsA);
            Files.deleteIfExists(idsB);
            Files.deleteIfExists(output);
            return new Result(clientMillis, clientSent + serverSent);
        } finally {
            if (toServer != null) {
                toServer.close();
            }
            if (toClient != null) {
                toClient.close();
            }
        }
    }

    private static Path writeIds(long[] ids) throws IOException {
        Path file = Files.createTempFile("matrix-ids", ".txt");
        StringBuilder sb = new StringBuilder(ids.length * 20);
        Arrays.stream(ids).forEach(id -> sb.append(id).append('\n'));
        Files.writeString(file, sb);
        return file;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
