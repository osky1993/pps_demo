package com.ppcdemo.platform.core.net;

import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * 控制面服务端（响应方节点）：HTTPS + 强制客户端证书（mTLS）。
 * 端点：POST /negotiation/propose（契约提议 → 审批决定）；
 *      POST /data/{taskId}/{key}（M2.5 #2：对方推送的任务载荷落本节点 DataStore）。
 * 采用 JDK 内置 HttpsServer：零依赖、行为可控；并发量到瓶颈时换装 Spring/Netty 只改本类。
 */
public final class MtlsNegotiationServer implements AutoCloseable {

    /** 数据端点单载荷上限：公钥/密文Σ 均为 KB 级，8MB 已含充分余量（防滥用）。 */
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    private final HttpsServer server;

    public MtlsNegotiationServer(int port, SSLContext sslContext,
                                 Function<TaskContract, PeerChannel.Decision> proposalHandler)
            throws IOException {
        this(port, sslContext, proposalHandler, null);
    }

    /** @param dataStore 非空时启用 /data 端点，对方推送落入其中（本方经 take 取件） */
    public MtlsNegotiationServer(int port, SSLContext sslContext,
                                 Function<TaskContract, PeerChannel.Decision> proposalHandler,
                                 com.ppcdemo.platform.core.data.BlockingDataStore dataStore)
            throws IOException {
        server = HttpsServer.create(new InetSocketAddress(port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                var engineParams = getSSLContext().getDefaultSSLParameters();
                engineParams.setNeedClientAuth(true);   // mTLS：无客户端证书直接握手失败
                params.setSSLParameters(engineParams);
            }
        });
        server.createContext("/negotiation/propose", exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String wire = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                PeerChannel.Decision decision;
                try {
                    decision = proposalHandler.apply(ContractCodec.decodeAsResponder(wire));
                } catch (RuntimeException e) {
                    decision = PeerChannel.Decision.reject("契约处理异常：" + e.getMessage());
                }
                byte[] body = ("approved=%s\nreason=%s"
                        .formatted(decision.approved(), decision.reason() == null ? "" : decision.reason()))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        if (dataStore != null) {
            server.createContext("/data", exchange -> {
                try (exchange) {
                    if (!"POST".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    // 路径：/data/{taskId}/{key}
                    String[] parts = exchange.getRequestURI().getPath().split("/");
                    if (parts.length != 4 || parts[2].isBlank() || parts[3].isBlank()) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    byte[] payload = exchange.getRequestBody().readNBytes(MAX_PAYLOAD_BYTES + 1);
                    if (payload.length > MAX_PAYLOAD_BYTES) {
                        exchange.sendResponseHeaders(413, -1);
                        return;
                    }
                    dataStore.put(parts[2], parts[3], payload);
                    exchange.sendResponseHeaders(204, -1);
                }
            });
        }
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
