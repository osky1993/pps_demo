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
 * 一期端点：POST /negotiation/propose（契约提议 → 审批决定）。
 * 采用 JDK 内置 HttpsServer：零依赖、行为可控；并发量到瓶颈时换装 Spring/Netty 只改本类。
 */
public final class MtlsNegotiationServer implements AutoCloseable {

    private final HttpsServer server;

    public MtlsNegotiationServer(int port, SSLContext sslContext,
                                 Function<TaskContract, PeerChannel.Decision> proposalHandler)
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
