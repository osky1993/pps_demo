package com.ppcdemo.platform.fate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 联邦在线评分客户端（M3 F4）：经 serving-proxy 的 HTTP 推理端点发起联合打分。
 * A 方传本方实时特征 + 主体 ID；B 方特征由 B 侧 Serving 按 ID 从其在线特征源取
 * （sendToRemoteFeatureData 携带路由键），双方特征不互见。
 *
 * 响应契约：{"retcode": 0, "retmsg": "success", "data": {"score": ..., "modelVersion": ...}}
 * retcode 非 0 时抛 ScoringException（含 retmsg，便于运维定位：105=远端 rpc 异常/模型未绑定）。
 */
public final class ScoringClient {

    public static final class ScoringException extends RuntimeException {
        private final int retcode;

        public ScoringException(int retcode, String message) {
            super("联邦评分失败 retcode=%d：%s".formatted(retcode, message));
            this.retcode = retcode;
        }

        public int retcode() {
            return retcode;
        }
    }

    public record Score(double value, String modelVersion, long latencyMillis) {
    }

    private static final Pattern RETCODE = Pattern.compile("\"retcode\":\\s*(-?\\d+)");
    private static final Pattern RETMSG = Pattern.compile("\"retmsg\":\\s*\"([^\"]*)\"");
    private static final Pattern SCORE = Pattern.compile("\"score\":\\s*([0-9.eE-]+)");
    private static final Pattern MODEL_VERSION = Pattern.compile("\"modelVersion\":\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String proxyBaseUrl;
    private final String serviceId;

    public ScoringClient(String proxyBaseUrl, String serviceId) {
        this.proxyBaseUrl = proxyBaseUrl;
        this.serviceId = serviceId;
    }

    /**
     * @param subjectId    评分主体（联邦路由键，B 侧据此取本方特征）
     * @param localFeatures A 方实时特征
     */
    public Score score(String subjectId, Map<String, Double> localFeatures) {
        String features = localFeatures.entrySet().stream()
                .map(e -> "\"%s\": %s".formatted(requireSafeKey(e.getKey()), e.getValue()))
                .collect(Collectors.joining(", "));
        String body = """
                {"head": {"serviceId": "%s"},
                 "body": {"featureData": {%s},
                          "sendToRemoteFeatureData": {"device_id": "%s"}}}
                """.formatted(serviceId, features, requireSafeKey(subjectId));

        long begin = System.nanoTime();
        String response = post("/federation/v1/inference", body);
        long latency = (System.nanoTime() - begin) / 1_000_000;

        Matcher rc = RETCODE.matcher(response);
        int retcode = rc.find() ? Integer.parseInt(rc.group(1)) : -1;
        if (retcode != 0) {
            Matcher msg = RETMSG.matcher(response);
            throw new ScoringException(retcode, msg.find() ? msg.group(1) : response);
        }
        Matcher score = SCORE.matcher(response);
        if (!score.find()) {
            throw new ScoringException(0, "响应无 score 字段：" + response);
        }
        Matcher version = MODEL_VERSION.matcher(response);
        return new Score(Double.parseDouble(score.group(1)),
                version.find() ? version.group(1) : null, latency);
    }

    /** 连通性探针：能与 proxy 完成一次请求/响应即为真（不要求模型已绑定）。 */
    public boolean reachable() {
        try {
            score("probe", Map.of("x0", 0.0));
            return true;
        } catch (ScoringException e) {
            return true;   // 结构化业务错误 = 通道可达
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String post(String path, String body) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(proxyBaseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Serving HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Serving 通信失败：" + proxyBaseUrl + path, e);
        }
    }

    private static String requireSafeKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.\\-]+")) {
            throw new IllegalArgumentException("特征键/主体 ID 仅允许字母数字与 _.-：" + key);
        }
        return key;
    }
}
