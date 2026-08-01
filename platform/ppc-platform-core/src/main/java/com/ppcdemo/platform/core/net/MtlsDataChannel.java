package com.ppcdemo.platform.core.net;

import com.ppcdemo.platform.core.data.BlockingDataStore;
import com.ppcdemo.platform.core.data.DataChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 跨机数据通道（M2.5 #2）：put = mTLS POST 推送到对方节点 /data 端点；
 * take = 本节点 DataStore 阻塞取件（对方推给我们的数据已落在本地，无需跨网轮询）。
 * put 对暂时性失败重试（对方节点可能尚在启动）；4xx 视为配置错误立即失败。
 */
public final class MtlsDataChannel implements DataChannel {

    private static final int PUT_RETRIES = 10;
    private static final long RETRY_BACKOFF_MILLIS = 1_000;

    private final HttpClient client;
    private final String peerBaseUrl;
    private final BlockingDataStore localStore;

    public MtlsDataChannel(String peerBaseUrl, SSLContext sslContext, BlockingDataStore localStore) {
        this.client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.peerBaseUrl = peerBaseUrl;
        this.localStore = localStore;
    }

    @Override
    public void put(String taskId, String key, byte[] payload) {
        URI uri = URI.create("%s/data/%s/%s".formatted(peerBaseUrl,
                URLEncoder.encode(taskId, StandardCharsets.UTF_8),
                URLEncoder.encode(key, StandardCharsets.UTF_8)));
        Exception last = null;
        for (int attempt = 0; attempt < PUT_RETRIES; attempt++) {
            try {
                HttpResponse<Void> response = client.send(
                        HttpRequest.newBuilder(uri)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                                .timeout(Duration.ofSeconds(30))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 204) {
                    return;
                }
                if (response.statusCode() >= 400 && response.statusCode() < 500) {
                    throw new IllegalStateException("数据推送被对方拒绝 HTTP %d：%s"
                            .formatted(response.statusCode(), uri));
                }
                last = new IOException("HTTP " + response.statusCode());
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("数据推送被中断", e);
            }
            try {
                Thread.sleep(RETRY_BACKOFF_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("数据推送被中断", e);
            }
        }
        throw new IllegalStateException("数据推送失败（重试 %d 次）：%s".formatted(PUT_RETRIES, uri), last);
    }

    @Override
    public byte[] take(String taskId, String key, long timeoutSeconds) {
        return localStore.take(taskId, key, timeoutSeconds);
    }
}
