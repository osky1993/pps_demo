package com.ppcdemo.platform.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.5 #3 终验：双节点全部经管理 REST 驱动——
 * local-stats 单节点闭环、joint-stats 跨节点（协商/数据通道走真实 mTLS）、审计与预算查询。
 */
class ManagementApiTest {

    @TempDir
    static Path dir;

    static PlatformNode nodeA;
    static PlatformNode nodeB;
    static ManagementServer mgmtA;
    static ManagementServer mgmtB;
    static final HttpClient http = HttpClient.newHttpClient();
    static Path csvA;
    static Path csvB;

    @BeforeAll
    static void boot() throws Exception {
        // 证书
        for (String n : new String[]{"nodeA", "nodeB"}) {
            exec("keytool", "-genkeypair", "-alias", n, "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", dir.resolve(n + ".p12").toString(),
                    "-storepass", "changeit", "-dname", "CN=" + n,
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "30");
            exec("keytool", "-exportcert", "-alias", n, "-keystore",
                    dir.resolve(n + ".p12").toString(), "-storepass", "changeit",
                    "-file", dir.resolve(n + ".cer").toString());
        }
        importTrust("nodeA", "nodeB");
        importTrust("nodeB", "nodeA");

        // 数据：A=客户名单，B=订单（1000×1000，交集 500，金额固定 10 元便于对账）
        csvA = dir.resolve("a.csv");
        csvB = dir.resolve("b.csv");
        StringBuilder a = new StringBuilder("id\n");
        StringBuilder b = new StringBuilder("id,yuan\n");
        for (int i = 0; i < 1000; i++) {
            a.append(i).append('\n');                      // A: 0..999
            b.append(i + 500).append(",").append(new BigDecimal("10.00")).append('\n'); // B: 500..1499
        }
        Files.writeString(csvA, a);
        Files.writeString(csvB, b);

        int controlA = freePort();
        int controlB = freePort();
        nodeA = new PlatformNode(config("A", controlA, controlB, "nodeA"));
        nodeB = new PlatformNode(config("B", controlB, controlA, "nodeB"));
        mgmtA = new ManagementServer(nodeA, 0);
        mgmtB = new ManagementServer(nodeB, 0);
    }

    private static NodeConfig config(String party, int controlPort, int peerControlPort, String cert) {
        return new NodeConfig(party, "mem", controlPort, 0,
                "https://localhost:" + peerControlPort,
                dir.resolve(cert + ".p12"), dir.resolve(cert + "-trust.p12"), "changeit",
                java.util.Set.of("csv:" + dir.resolve("b.csv") + "?idCol=0&valueCol=1"),
                10.0, 180);
    }

    @AfterAll
    static void teardown() {
        for (AutoCloseable c : new AutoCloseable[]{mgmtA, mgmtB, nodeA, nodeB}) {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Exception ignored) {
                // 收尾
            }
        }
    }

    @Test
    void 本地统计任务经REST闭环() throws Exception {
        Properties created = post(mgmtA, "/api/task/local-stats", """
                dataset=csv:%s?idCol=0
                epsilon=1.0
                clampUpper=100000
                """.formatted(csvA));
        String taskId = created.getProperty("taskId");
        double released = Double.parseDouble(created.getProperty("released"));
        // count 型统计：真值 1000，ε=1 敏感度 1e5 噪声界 20b
        assertTrue(Math.abs(released - 1000) < 20 * 100_000, "出库值异常：" + released);

        assertEquals("SUCCEEDED", get(mgmtA, "/api/task/" + taskId).getProperty("state"));
        assertEquals(String.valueOf(released),
                get(mgmtA, "/api/task/" + taskId + "/result").getProperty("released"));
        assertTrue(getRaw(mgmtA, "/api/task/" + taskId + "/audit").contains("RELEASED"));
    }

    @Test
    void 跨节点联合统计经REST全流程() throws Exception {
        // 1. A 发起（协商经真实 mTLS 控制面；B 按数据集白名单自动审批）
        String peerDataset = "csv:" + csvB + "?idCol=0&valueCol=1";
        Properties created = post(mgmtA, "/api/task/joint-stats", """
                responder=B
                dataset=csv:%s?idCol=0
                peerDataset=%s
                epsilon=1.0
                clampUpper=100000
                """.formatted(csvA, peerDataset));
        String taskId = created.getProperty("taskId");
        assertEquals("READY", created.getProperty("state"), "白名单内应自动审批通过");
        assertEquals("READY", get(mgmtB, "/api/task/" + taskId).getProperty("state"));

        double budgetBefore = Double.parseDouble(
                get(mgmtA, "/api/budget?dataset=csv:" + csvA + "?idCol=0").getProperty("remaining"));

        // 2. 双方各自经 REST 触发执行（引擎端点由运行手册约定；B 异步、A 同步）
        int portA = freePort();
        int portB = freePort();
        post(mgmtB, "/api/task/" + taskId + "/run", """
                role=client
                localPort=%d
                peerHost=127.0.0.1
                peerPort=%d
                peerSize=1000
                """.formatted(portB, portA));
        Properties runResult = post(mgmtA, "/api/task/" + taskId + "/run", """
                role=server
                localPort=%d
                peerHost=127.0.0.1
                peerPort=%d
                peerSize=1000
                """.formatted(portA, portB));

        // 3. 对账：交集 500 人 × 10.00 元 = 500000 分
        double released = Double.parseDouble(runResult.getProperty("released"));
        assertTrue(Math.abs(released - 500_000) < 20 * 100_000, "出库值异常：" + released);
        awaitState(mgmtB, taskId, "SUCCEEDED", 30);
        assertEquals("SUCCEEDED", get(mgmtA, "/api/task/" + taskId).getProperty("state"));
        // 4. 预算已扣减 ε=1（与本类其它测试的消耗解耦，断言差值）
        double budgetAfter = Double.parseDouble(
                get(mgmtA, "/api/budget?dataset=csv:" + csvA + "?idCol=0").getProperty("remaining"));
        assertEquals(1.0, budgetBefore - budgetAfter, 1e-9);
    }

    // ── HTTP 工具 ──
    private static Properties post(ManagementServer server, String path, String body) throws Exception {
        return parse(send(HttpRequest.newBuilder(uri(server, path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()));
    }

    private static Properties get(ManagementServer server, String path) throws Exception {
        return parse(send(HttpRequest.newBuilder(uri(server, path)).GET().build()));
    }

    private static String getRaw(ManagementServer server, String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(server, path)).GET().build());
    }

    private static URI uri(ManagementServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "HTTP 失败：" + response.body());
        return response.body();
    }

    private static Properties parse(String body) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(body));
        return p;
    }

    private static void awaitState(ManagementServer server, String taskId,
                                   String expected, int seconds) throws Exception {
        for (int i = 0; i < seconds * 2; i++) {
            if (expected.equals(get(server, "/api/task/" + taskId).getProperty("state"))) {
                return;
            }
            Thread.sleep(500);
        }
        assertEquals(expected, get(server, "/api/task/" + taskId).getProperty("state"));
    }

    private static void importTrust(String owner, String trusted) throws Exception {
        exec("keytool", "-importcert", "-noprompt", "-alias", trusted,
                "-keystore", dir.resolve(owner + "-trust.p12").toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", dir.resolve(trusted + ".cer").toString());
    }

    private static void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "keytool 超时");
        assertEquals(0, p.exitValue(), "keytool 失败：" + out);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
