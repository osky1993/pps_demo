package com.ppcdemo.platform.fate;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ScoringClient 契约测试：对 fake serving-proxy 验证请求构造与响应解析；
 * 另含对活体 proxy 的连通性验证（未起则跳过）。
 */
class ScoringClientTest {

    static HttpServer fakeProxy;
    static int port;
    static final AtomicReference<String> lastRequest = new AtomicReference<>();
    static final AtomicReference<String> nextResponse = new AtomicReference<>(
            "{\"retcode\": 0, \"retmsg\": \"success\", \"data\": {\"score\": 0.8231, \"modelVersion\": \"v20260801\"}}");

    @BeforeAll
    static void start() throws Exception {
        fakeProxy = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeProxy.createContext("/federation/v1/inference", exchange -> {
            try (exchange) {
                lastRequest.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                byte[] bytes = nextResponse.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        fakeProxy.start();
        port = fakeProxy.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        fakeProxy.stop(0);
    }

    @Test
    void 评分请求构造与响应解析() {
        var client = new ScoringClient("http://127.0.0.1:" + port, "risk-model");
        var score = client.score("user-1001", Map.of("income", 12000.0));

        assertEquals(0.8231, score.value(), 1e-9);
        assertEquals("v20260801", score.modelVersion());
        assertTrue(score.latencyMillis() >= 0);

        String request = lastRequest.get();
        assertTrue(request.contains("\"serviceId\": \"risk-model\""));
        assertTrue(request.contains("\"income\": 12000.0"), "本方特征入 featureData");
        assertTrue(request.contains("\"device_id\": \"user-1001\""), "主体 ID 走远端路由键");
    }

    @Test
    void 业务错误码抛结构化异常() {
        nextResponse.set("{\"retcode\": 105, \"retmsg\": \"remote rpc exception\"}");
        var client = new ScoringClient("http://127.0.0.1:" + port, "risk-model");
        var e = assertThrows(ScoringClient.ScoringException.class,
                () -> client.score("u1", Map.of("x", 1.0)));
        assertEquals(105, e.retcode());
        nextResponse.set("{\"retcode\": 0, \"retmsg\": \"success\", \"data\": {\"score\": 0.5}}");
    }

    @Test
    void 特征键注入被拦截() {
        var client = new ScoringClient("http://127.0.0.1:" + port, "risk-model");
        assertThrows(IllegalArgumentException.class,
                () -> client.score("u1", Map.of("x\": 1, \"evil", 1.0)));
    }

    @Test
    void 活体proxy连通性() {
        String url = System.getProperty("serving.proxy.url", "http://localhost:8059");
        var client = new ScoringClient(url, "probe");
        assumeTrue(probe(client), "serving-proxy 未起，跳过（起环境：deploy/fate/docker-compose-serving.yml）");
        assertTrue(client.reachable(), "活体 proxy 应可完成请求/响应往返");
    }

    private static boolean probe(ScoringClient client) {
        try {
            return client.reachable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
