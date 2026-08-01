package com.ppcdemo.platform.gateway.http;

import com.ppcdemo.platform.gateway.AsyncJobGateway;
import com.ppcdemo.platform.gateway.QueryGateway;
import com.ppcdemo.platform.gateway.api.GatewayException;
import com.ppcdemo.platform.gateway.api.PpsRequest;
import com.ppcdemo.platform.gateway.job.JobRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * PPS 接入 HTTP 端点（M5，修 G1：绑定可配置地址，默认 0.0.0.0 供跨机调用）。
 *
 * 生产应置于 mTLS 之后或前置 TLS 终止；请求体本身已 HMAC 签名（防篡改/重放），
 * TLS 负责机密性。端点：POST /pps/v1/query（properties 文本，与既有控制面同风格）。
 */
public final class PpsGatewayServer implements AutoCloseable {

    private final HttpServer server;

    public PpsGatewayServer(String bindHost, int port, QueryGateway gateway) throws IOException {
        this(bindHost, port, gateway, null);
    }

    /** @param async 可空；非空则注册异步作业端点（M5-P2） */
    public PpsGatewayServer(String bindHost, int port, QueryGateway gateway,
                            AsyncJobGateway async) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        if (gateway != null) {
            server.createContext("/pps/v1/query", exchange -> handleQuery(exchange, gateway));
        }
        if (async != null) {
            server.createContext("/pps/v1/jobs", exchange -> handleJobs(exchange, async));
        }
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleQuery(HttpExchange exchange, QueryGateway gateway) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "error=method-not-allowed");
                return;
            }
            Properties p = new Properties();
            p.load(new StringReader(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)));
            PpsRequest req = new PpsRequest(
                    p.getProperty("appId"), p.getProperty("capability"), p.getProperty("reqId"),
                    Long.parseLong(p.getProperty("timestamp", "0")), p.getProperty("nonce"),
                    p.getProperty("body"), p.getProperty("signature"));
            try {
                QueryGateway.Response r = gateway.handle(req);
                respond(exchange, 200, "hit=%s%nvalue=%s%nreplayed=%s%nappQuotaRemaining=%.0f"
                        .formatted(r.hit(), r.value() == null ? "" : r.value(),
                                r.replayed(), r.appQuotaRemaining()));
            } catch (GatewayException e) {
                respond(exchange, e.reason().httpStatus,
                        "error=%s%nreason=%s".formatted(e.getMessage(), e.reason()));
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "error=internal");
        }
    }

    /**
     * 异步作业：POST /pps/v1/jobs（提交）、GET /pps/v1/jobs/{id}（轮询）、
     * GET /pps/v1/jobs/{id}/result?cursor=&limit=（结果拉取）。
     */
    private void handleJobs(HttpExchange exchange, AsyncJobGateway async) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String[] parts = path.split("/");   // /pps/v1/jobs[/{id}[/result]]
            try {
                if ("POST".equals(method) && parts.length == 4) {
                    Properties p = readBody(exchange);
                    PpsRequest req = toRequest(p);
                    var r = async.submit(req, p.getProperty("callbackUrl"));
                    respond(exchange, 200, "jobId=%s%nstate=%s".formatted(r.jobId(), r.state()));
                } else if ("GET".equals(method) && parts.length == 5) {
                    String appId = requireHeader(exchange, "X-App-Id");
                    var job = async.poll(appId, parts[4]);
                    respond(exchange, 200, "jobId=%s%nstate=%s%nresultKind=%s%nsummary=%s"
                            .formatted(job.reqId(), job.state(),
                                    job.resultKind() == null ? "" : job.resultKind(),
                                    job.resultRef() == null ? "" : job.resultRef()));
                } else if ("GET".equals(method) && parts.length == 6 && parts[5].equals("result")) {
                    String appId = requireHeader(exchange, "X-App-Id");
                    Properties q = parseQuery(exchange.getRequestURI().getQuery());
                    var page = async.result(appId, parts[4],
                            Integer.parseInt(q.getProperty("cursor", "0")),
                            Integer.parseInt(q.getProperty("limit", "1000")));
                    respond(exchange, 200, "nextCursor=%d%nhasMore=%s%nlines=%s"
                            .formatted(page.nextCursor(), page.hasMore(),
                                    page.lines().stream().collect(Collectors.joining(""))));
                } else {
                    respond(exchange, 404, "error=not-found");
                }
            } catch (GatewayException e) {
                respond(exchange, e.reason().httpStatus, "error=%s%nreason=%s"
                        .formatted(e.getMessage(), e.reason()));
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "error=internal");
        }
    }

    private static Properties readBody(HttpExchange exchange) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8)));
        return p;
    }

    private static PpsRequest toRequest(Properties p) {
        return new PpsRequest(p.getProperty("appId"), p.getProperty("capability"),
                p.getProperty("reqId"), Long.parseLong(p.getProperty("timestamp", "0")),
                p.getProperty("nonce"), p.getProperty("body"), p.getProperty("signature"));
    }

    private static String requireHeader(HttpExchange exchange, String name) {
        String v = exchange.getRequestHeaders().getFirst(name);
        if (v == null || v.isBlank()) {
            throw new GatewayException(GatewayException.Reason.BAD_REQUEST, "缺少请求头：" + name);
        }
        return v;
    }

    private static Properties parseQuery(String query) throws IOException {
        Properties p = new Properties();
        if (query != null) {
            p.load(new StringReader(query.replace('&', '\n')));
        }
        return p;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
