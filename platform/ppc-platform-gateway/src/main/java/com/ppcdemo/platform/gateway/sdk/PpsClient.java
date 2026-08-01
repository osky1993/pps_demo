package com.ppcdemo.platform.gateway.sdk;

import com.ppcdemo.platform.gateway.auth.HmacSigner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/**
 * PPS Java SDK v0（M5 §3.6）：业务系统调用隐私计算能力的轻量客户端（仅 JDK HttpClient）。
 *
 * 职责：规范化签名、超时、错误分类；**不做业务语义解释**。
 * 业务调用退化为「能力 id + 业务参数 + 幂等键」——ε/协议/输出粒度对业务完全不可见。
 *
 * <pre>
 * PpsClient pps = new PpsClient("https://pps-node:8443", "risk-system", secret);
 * var r = pps.query("credit-check", customerId, bizOrderId);   // 幂等键 = 业务单据号
 * if (r.hit()) use(r.value());
 * </pre>
 */
public final class PpsClient {

    public record QueryResult(boolean hit, String value, boolean replayed,
                              double appQuotaRemaining) {
    }

    public static final class PpsCallException extends RuntimeException {
        private final int httpStatus;

        public PpsCallException(int httpStatus, String message) {
            super("PPS 调用失败 HTTP %d：%s".formatted(httpStatus, message));
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }

        /** 4xx（认证/授权/配额/参数）不应重试；5xx/网络可带同一 reqId 安全重试。 */
        public boolean retryable() {
            return httpStatus >= 500;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String endpoint;
    private final String appId;
    private final String secret;

    public PpsClient(String endpoint, String appId, String secret) {
        this.endpoint = endpoint;
        this.appId = appId;
        this.secret = secret;
    }

    /** @param requestId 幂等键（业务单据号）——带同一 requestId 重试保证不重复执行 */
    public QueryResult query(String capability, String key, String requestId) {
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String canonical = HmacSigner.canonical(appId, ts, nonce, capability, requestId, key);
        String signature = HmacSigner.sign(secret, canonical);
        String body = """
                appId=%s
                capability=%s
                reqId=%s
                timestamp=%d
                nonce=%s
                body=%s
                signature=%s
                """.formatted(appId, capability, requestId, ts, nonce, key, signature);
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                            URI.create(endpoint + "/pps/v1/query"))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Properties p = new Properties();
            p.load(new java.io.StringReader(resp.body()));
            if (resp.statusCode() != 200) {
                throw new PpsCallException(resp.statusCode(),
                        p.getProperty("error", resp.body()));
            }
            return new QueryResult(
                    Boolean.parseBoolean(p.getProperty("hit", "false")),
                    emptyToNull(p.getProperty("value", "")),
                    Boolean.parseBoolean(p.getProperty("replayed", "false")),
                    Double.parseDouble(p.getProperty("appQuotaRemaining", "0")));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PpsCallException(503, "网络异常：" + e.getMessage());
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
