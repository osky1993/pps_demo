package com.ppcdemo.platform.core.keyvault;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VaultTransitEnvelope 客户端契约测试：对 fake Vault（模拟 transit encrypt/decrypt 语义 +
 * token 校验）验证请求/响应处理。真实 Vault 的部署差异仅在 URL/token/策略（手册 §Vault）。
 */
class VaultTransitEnvelopeTest {

    static HttpServer fakeVault;
    static int port;
    static final String TOKEN = "s.test-token";

    @BeforeAll
    static void startFakeVault() throws Exception {
        fakeVault = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeVault.createContext("/v1/transit/", exchange -> {
            try (exchange) {
                if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Vault-Token"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getPath();
                String response;
                if (path.contains("/encrypt/")) {
                    String plaintext = extract(body, "plaintext");
                    // fake 密文 = vault:v1: + base64 反转（可逆变换，模拟不透明密文）
                    response = "{\"data\": {\"ciphertext\": \"vault:v1:%s\"}}"
                            .formatted(new StringBuilder(plaintext).reverse());
                } else {
                    String ciphertext = extract(body, "ciphertext");
                    String reversed = new StringBuilder(
                            ciphertext.substring("vault:v1:".length())).reverse().toString();
                    response = "{\"data\": {\"plaintext\": \"%s\"}}".formatted(reversed);
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        fakeVault.start();
        port = fakeVault.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        fakeVault.stop(0);
    }

    @Test
    void 信封往返一致且密文不透明() {
        VaultTransitEnvelope envelope = new VaultTransitEnvelope(
                "http://127.0.0.1:" + port, TOKEN, "ppc-node");
        byte[] material = "paillier-key-material-测试-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = envelope.seal(material);
        assertTrue(new String(sealed, StandardCharsets.UTF_8).startsWith("vault:v1:"),
                "密文应为 Vault 记号格式");
        assertArrayEquals(material, envelope.unseal(sealed));
    }

    @Test
    void 与PersistentKeyVault组合走通() {
        VaultTransitEnvelope envelope = new VaultTransitEnvelope(
                "http://127.0.0.1:" + port, TOKEN, "ppc-node");
        var vault = new PersistentKeyVault(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                        "vault-kv-" + System.nanoTime()), envelope);
        byte[] pubkey = vault.createTaskKey("vt1", 2048);
        byte[] payload = com.ppcdemo.platform.core.phe.CipherAggregator
                .aggregateForTransfer(pubkey, new long[]{42, 58});
        org.junit.jupiter.api.Assertions.assertEquals(100, vault.decrypt("vt1", payload));
        vault.destroy("vt1");
    }

    @Test
    void 错误token被拒() {
        VaultTransitEnvelope bad = new VaultTransitEnvelope(
                "http://127.0.0.1:" + port, "wrong-token", "ppc-node");
        assertThrows(IllegalStateException.class, () -> bad.seal(new byte[]{1, 2, 3}));
    }

    private static String extract(String json, String field) {
        int at = json.indexOf("\"" + field + "\"");
        int start = json.indexOf('"', json.indexOf(':', at) + 1) + 1;
        return json.substring(start, json.indexOf('"', start));
    }
}
