package com.ppcdemo.platform.core.net;

import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.negotiation.PeerChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/** 控制面客户端（发起方节点）：携带本方证书访问对方节点（mTLS）。 */
public final class MtlsPeerChannel implements PeerChannel {

    private final HttpClient client;
    private final URI proposeUri;

    public MtlsPeerChannel(String peerBaseUrl, SSLContext sslContext) {
        this.client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.proposeUri = URI.create(peerBaseUrl + "/negotiation/propose");
    }

    @Override
    public Decision propose(TaskContract contract) {
        try {
            HttpRequest request = HttpRequest.newBuilder(proposeUri)
                    .POST(HttpRequest.BodyPublishers.ofString(ContractCodec.encode(contract)))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return Decision.reject("对方节点返回 HTTP " + response.statusCode());
            }
            Properties p = new Properties();
            p.load(new java.io.StringReader(response.body()));
            boolean approved = Boolean.parseBoolean(p.getProperty("approved", "false"));
            String reason = p.getProperty("reason", "");
            return new Decision(approved, reason.isBlank() ? null : reason);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Decision.reject("控制面通信失败：" + e.getMessage());
        }
    }
}
