package com.ppcdemo.platform.core.net;

import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 安全门禁：控制面 mTLS——双向证书互信可通、无客户端证书必须被拒。
 * 证书用 keytool 即席生成（自签 + 证书指纹式互信，与 deploy/gen-certs.sh 同构）。
 */
class MtlsChannelTest {

    @TempDir
    static Path certDir;

    static SSLContext nodeA;
    static SSLContext nodeB;
    static MtlsNegotiationServer server;
    static com.ppcdemo.platform.core.data.BlockingDataStore storeB;

    @BeforeAll
    static void setupCerts() throws Exception {
        genKeyPair("nodeA");
        genKeyPair("nodeB");
        exportCert("nodeA");
        exportCert("nodeB");
        importTrust("nodeA", "nodeB");   // A 信任 B
        importTrust("nodeB", "nodeA");   // B 信任 A
        nodeA = TlsContexts.build(certDir.resolve("nodeA.p12"), "changeit".toCharArray(),
                certDir.resolve("nodeA-trust.p12"));
        nodeB = TlsContexts.build(certDir.resolve("nodeB.p12"), "changeit".toCharArray(),
                certDir.resolve("nodeB-trust.p12"));
        storeB = new com.ppcdemo.platform.core.data.BlockingDataStore();
        server = new MtlsNegotiationServer(0, nodeB,
                contract -> PeerChannel.Decision.approve(), storeB);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void 持证客户端提议成功() {
        MtlsPeerChannel channel = new MtlsPeerChannel("https://localhost:" + server.port(), nodeA);
        PeerChannel.Decision decision = channel.propose(sampleContract());
        assertTrue(decision.approved(), "双向互信应审批通过：" + decision.reason());
    }

    @Test
    void 无证书客户端被拒() throws Exception {
        // 只信任 B（能验服务端）但不出示本方证书 → 服务端 needClientAuth 应拒绝握手
        SSLContext trustOnly = SSLContext.getInstance("TLSv1.3");
        var tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        var trustStore = java.security.KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(certDir.resolve("nodeA-trust.p12"))) {
            trustStore.load(in, "changeit".toCharArray());
        }
        tmf.init(trustStore);
        trustOnly.init(null, tmf.getTrustManagers(), null);

        HttpClient bare = HttpClient.newBuilder().sslContext(trustOnly).build();
        assertThrows(IOException.class, () -> bare.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + server.port() + "/negotiation/propose"))
                        .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString()), "无客户端证书必须握手失败");
    }

    @Test
    void 数据端点推送落对方存储且取走即删() {
        var channelA = new MtlsDataChannel("https://localhost:" + server.port(), nodeA,
                new com.ppcdemo.platform.core.data.BlockingDataStore());
        byte[] payload = "pubkey-bytes-测试".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        channelA.put("t-data", "pubkey", payload);

        assertTrue(java.util.Arrays.equals(payload, storeB.take("t-data", "pubkey", 10)),
                "B 本地取件必须与 A 推送一致");
        assertThrows(IllegalStateException.class, () -> storeB.take("t-data", "pubkey", 1),
                "取走即删：二次取件应超时");
    }

    @Test
    void 数据端点非法路径被拒() {
        var channelA = new MtlsDataChannel("https://localhost:" + server.port(), nodeA,
                new com.ppcdemo.platform.core.data.BlockingDataStore());
        assertThrows(IllegalStateException.class,
                () -> channelA.put("", "", new byte[]{1}), "空 taskId/key 应 4xx 拒绝");
    }

    @Test
    void 契约编解码含视角互换() {
        TaskContract decoded = ContractCodec.decodeAsResponder(ContractCodec.encode(sampleContract()));
        assertEquals("ds://B/orders", decoded.datasetLocal(), "响应方视角：对方的 peer 是本方的 local");
        assertEquals("ds://A/customers", decoded.datasetPeer());
        assertEquals(TaskContract.OutputGranularity.CARDINALITY_ONLY, decoded.outputGranularity());
    }

    private static TaskContract sampleContract() {
        return new TaskContract("t-mtls", TaskContract.TaskType.PSI, "A", "B",
                "ds://A/customers", "ds://B/orders", "rr22",
                TaskContract.OutputGranularity.CARDINALITY_ONLY, null, null, false, null);
    }

    private static void genKeyPair(String name) throws Exception {
        exec("keytool", "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", certDir.resolve(name + ".p12").toString(),
                "-storepass", "changeit", "-dname", "CN=" + name + ",O=ppc-demo",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "365");
    }

    private static void exportCert(String name) throws Exception {
        exec("keytool", "-exportcert", "-alias", name,
                "-keystore", certDir.resolve(name + ".p12").toString(), "-storepass", "changeit",
                "-file", certDir.resolve(name + ".cer").toString());
    }

    private static void importTrust(String owner, String trusted) throws Exception {
        exec("keytool", "-importcert", "-noprompt", "-alias", trusted,
                "-keystore", certDir.resolve(owner + "-trust.p12").toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", certDir.resolve(trusted + ".cer").toString());
    }

    private static void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "keytool 超时");
        assertEquals(0, p.exitValue(), "keytool 失败：" + out);
    }
}
