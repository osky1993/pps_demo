package com.ppcdemo.platform.fate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FATE Flow 1.11 REST 客户端（M3 F2）。只与**本方** Flow 交互（跨方协调走平台契约，ADR 见 M3 §3.2）。
 * 响应解析用锚点/正则摘取所需字段（与 Vault 客户端同模式）；字段缺失即抛错，不静默。
 */
public class FateFlowClient {

    private static final Pattern JOB_ID = Pattern.compile("\"jobId\":\\s*\"(\\d+)\"");
    private static final Pattern F_STATUS = Pattern.compile("\"f_status\":\\s*\"(\\w+)\"");
    private static final Pattern AUC = Pattern.compile("\"auc\",?\\s*[,:]?\\s*([0-9.]+)|\\[\\s*\"auc\"\\s*,\\s*([0-9.]+)");
    private static final Pattern MODEL_ID = Pattern.compile("\"model_id\":\\s*\"([^\"]+)\"");
    private static final Pattern MODEL_VERSION = Pattern.compile("\"model_version\":\\s*\"([^\"]+)\"");
    private static final Pattern INTERSECT_COUNT = Pattern.compile("\"intersect_count\",?\\s*[,:]?\\s*(\\d+)|\\[\\s*\"intersect_count\"\\s*,\\s*(\\d+)");

    public record SubmitResult(String jobId, String modelId, String modelVersion) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;

    public FateFlowClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean reachable() {
        try {
            return post("/v1/version/get", "{}").contains("\"retcode\":0");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 服务端本地文件上传（use_local_data=0 语义）：数据文件经共享卷交付本方 Flow——
     * 数据不出本方，是部署形态而非旁路（手册注明卷挂载约定）。
     */
    public void uploadServerLocal(String serverPath, String namespace, String table,
                                  int head, int partition) {
        String body = """
                {"file": "%s", "head": %d, "partition": %d,
                 "namespace": "%s", "table_name": "%s", "drop": 1, "use_local_data": 0}
                """.formatted(serverPath, head, partition, namespace, table);
        String response = post("/v1/data/upload", body);
        requireOk(response, "数据上传失败");
    }

    /** 提交任务，返回 jobId + 模型坐标（注册表登记用）。 */
    public SubmitResult submitJob(String confJson, String dslJson) {
        String body = "{\"job_runtime_conf\": %s, \"job_dsl\": %s}".formatted(confJson, dslJson);
        String response = post("/v1/job/submit", body);
        requireOk(response, "任务提交失败");
        Matcher m = JOB_ID.matcher(response);
        if (!m.find()) {
            throw new IllegalStateException("提交响应无 jobId：" + trim(response));
        }
        Matcher mid = MODEL_ID.matcher(response);
        Matcher mver = MODEL_VERSION.matcher(response);
        return new SubmitResult(m.group(1),
                mid.find() ? mid.group(1) : null,
                mver.find() ? mver.group(1) : null);
    }

    /** 样本对齐基数（intersection_0 的 intersect_count）；解析不到返回 -1。 */
    public long intersectCount(String jobId, int guestPartyId) {
        String response = post("/v1/tracking/component/metric/all", """
                {"job_id": "%s", "role": "guest", "party_id": %d, "component_name": "intersection_0"}
                """.formatted(jobId, guestPartyId));
        Matcher m = INTERSECT_COUNT.matcher(response);
        if (m.find()) {
            return Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return -1;
    }

    /** waiting | running | success | failed | canceled */
    public String jobStatus(String jobId) {
        String response = post("/v1/job/query", "{\"job_id\": \"%s\"}".formatted(jobId));
        Matcher m = F_STATUS.matcher(response);
        if (!m.find()) {
            throw new IllegalStateException("状态查询无 f_status：" + trim(response));
        }
        return m.group(1);
    }

    public void cancelJob(String jobId) {
        post("/v1/job/stop", "{\"job_id\": \"%s\"}".formatted(jobId));
    }

    /** 评估组件 AUC（guest 侧）；解析不到返回 -1（训练可无评估组件）。 */
    public double evaluationAuc(String jobId, int guestPartyId) {
        String response = post("/v1/tracking/component/metric/all", """
                {"job_id": "%s", "role": "guest", "party_id": %d, "component_name": "evaluation_0"}
                """.formatted(jobId, guestPartyId));
        Matcher m = AUC.matcher(response);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /** guest 侧 LR 模型概要（键名列表，供审计与注册表指纹参考）。 */
    public String outputModelSummary(String jobId, int guestPartyId, String component) {
        String response = post("/v1/tracking/component/output/model", """
                {"job_id": "%s", "role": "guest", "party_id": %d, "component_name": "%s"}
                """.formatted(jobId, guestPartyId, component));
        return trim(response);
    }

    protected String post(String path, String body) {
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMinutes(2))
                    .build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes = response.body().readNBytes(4 * 1024 * 1024);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("FATE Flow HTTP %d：%s"
                        .formatted(response.statusCode(), path));
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("FATE Flow 通信失败：" + path, e);
        }
    }

    private static void requireOk(String response, String message) {
        if (!response.contains("\"retcode\":0") && !response.contains("\"retcode\": 0")) {
            throw new IllegalStateException(message + "：" + trim(response));
        }
    }

    private static String trim(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
