package com.ppcdemo.platform.gateway.job;

import com.ppcdemo.platform.gateway.auth.AppRegistry;
import com.ppcdemo.platform.gateway.auth.HmacSigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * 终态回调发送（M5 §3.4）：HMAC-SHA256 签名 + 指数退避重试。
 * 业务系统用**同一 appSecret** 验证回调确实来自 PPS（防伪造终态）。
 * 回调不可达不影响作业本身——轮询兜底始终可用（回调仅为优化）。
 */
public final class CallbackSender {

    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MILLIS = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final AppRegistry apps;
    private final java.util.function.LongSupplier clock;

    public CallbackSender(AppRegistry apps, java.util.function.LongSupplier clock) {
        this.apps = apps;
        this.clock = clock;
    }

    /** 规范串（业务系统据此验签）：appId\ntimestamp\nnonce\njobId\nstate\nSHA256(summary) */
    public static String canonicalCallback(String appId, long ts, String nonce,
                                           String jobId, String state, String summary) {
        return HmacSigner.canonical(appId, ts, nonce, jobId, state, summary);
    }

    /** @return 是否送达成功；重试耗尽返回 false（不抛，作业状态不受影响）。 */
    public boolean send(String appId, String callbackUrl, String jobId, String state, String summary) {
        long ts = clock.getAsLong();
        String nonce = UUID.randomUUID().toString();
        String canonical = canonicalCallback(appId, ts, nonce, jobId, state, summary);
        String signature = apps.signForApp(appId, canonical);
        String body = """
                jobId=%s
                state=%s
                summary=%s
                timestamp=%d
                nonce=%s
                signature=%s
                """.formatted(jobId, state, summary, ts, nonce, signature);

        long backoff = BASE_BACKOFF_MILLIS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<Void> resp = http.send(HttpRequest.newBuilder(URI.create(callbackUrl))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(10))
                        .build(), HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return true;
                }
            } catch (Exception retryable) {
                // 网络异常 → 退避重试
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(backoff);
                backoff *= 2;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
