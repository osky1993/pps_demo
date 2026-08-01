package com.ppcdemo.platform.gateway.http;

import com.ppcdemo.platform.gateway.QueryGateway;
import com.ppcdemo.platform.gateway.api.GatewayException;
import com.ppcdemo.platform.gateway.api.PpsRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * PPS 接入 HTTP 端点（M5，修 G1：绑定可配置地址，默认 0.0.0.0 供跨机调用）。
 *
 * 生产应置于 mTLS 之后或前置 TLS 终止；请求体本身已 HMAC 签名（防篡改/重放），
 * TLS 负责机密性。端点：POST /pps/v1/query（properties 文本，与既有控制面同风格）。
 */
public final class PpsGatewayServer implements AutoCloseable {

    private final HttpServer server;

    public PpsGatewayServer(String bindHost, int port, QueryGateway gateway) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.createContext("/pps/v1/query", exchange -> handleQuery(exchange, gateway));
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
