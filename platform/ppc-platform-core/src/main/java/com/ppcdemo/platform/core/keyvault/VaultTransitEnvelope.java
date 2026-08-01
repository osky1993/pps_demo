package com.ppcdemo.platform.core.keyvault;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * HashiCorp Vault Transit 信封（M2.5 #4）：加密即服务，主密钥永不出 Vault。
 * API：POST {base}/v1/transit/encrypt/{key} {"plaintext": base64} → data.ciphertext（vault:v1:..）
 *      POST {base}/v1/transit/decrypt/{key} {"ciphertext": ..}   → data.plaintext（base64）
 * 响应为 JSON，此处只需摘取单一字段，用锚点截取而不引入 JSON 依赖（字段值为 base64/纯文本无转义）。
 * 生产 Vault 的部署与 token 策略见部署手册 §Vault。
 */
public final class VaultTransitEnvelope implements EnvelopeEncryptor {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String token;
    private final String keyName;

    public VaultTransitEnvelope(String baseUrl, String token, String keyName) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.keyName = keyName;
    }

    @Override
    public byte[] seal(byte[] plaintext) {
        String body = "{\"plaintext\": \"%s\"}"
                .formatted(Base64.getEncoder().encodeToString(plaintext));
        String ciphertext = extract(call("encrypt", body), "ciphertext");
        return ciphertext.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] unseal(byte[] sealed) {
        String body = "{\"ciphertext\": \"%s\"}".formatted(new String(sealed, StandardCharsets.UTF_8));
        return Base64.getDecoder().decode(extract(call("decrypt", body), "plaintext"));
    }

    private String call(String operation, String body) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create("%s/v1/transit/%s/%s".formatted(baseUrl, operation, keyName)))
                    .header("X-Vault-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Vault %s 失败 HTTP %d：%s"
                        .formatted(operation, response.statusCode(), response.body()));
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Vault 通信失败：" + operation, e);
        }
    }

    /** 从 Vault JSON 响应中摘取 "field": "value"（值为 base64/vault 记号，无 JSON 转义字符）。 */
    private static String extract(String json, String field) {
        String anchor = "\"" + field + "\"";
        int at = json.indexOf(anchor);
        if (at < 0) {
            throw new IllegalStateException("Vault 响应缺少字段 " + field + "：" + json);
        }
        int start = json.indexOf('"', json.indexOf(':', at) + 1) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
