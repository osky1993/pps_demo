package com.ppcdemo.engine.psi;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.psi.ProtocolTlsProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.5 质量门禁：协议面 TLS 隧道。
 * 正向：双方互信证书 → 求交真值一致；
 * 负向：client 信任库装错证书 → 引擎必须失败（证明 TLS 真正强制而非摆设）；
 * 默认关闭：不传 ProtocolTlsProfile 走明文（既有全部测试即回归证明）。
 */
class TlsProtocolPsiTest {

    @TempDir
    static Path dir;

    @BeforeAll
    static void certs() throws Exception {
        for (String node : new String[]{"nodeA", "nodeB", "mallory"}) {
            exec("keytool", "-genkeypair", "-alias", node, "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", dir.resolve(node + ".p12").toString(),
                    "-storepass", "changeit", "-dname", "CN=" + node,
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "30");
            exec("keytool", "-exportcert", "-alias", node,
                    "-keystore", dir.resolve(node + ".p12").toString(), "-storepass", "changeit",
                    "-file", dir.resolve(node + ".cer").toString());
        }
        trust("nodeA-trust", "nodeB");
        trust("nodeB-trust", "nodeA");
        trust("nodeB-badtrust", "mallory");   // B 错信第三方证书
    }

    @Test
    void TLS互信下求交真值一致() throws Exception {
        var sets = IdSetGenerator.generate(99L, 5_000, 5_000, 0.4);
        int nettyA = freePort();
        int nettyB = freePort();
        int tlsA = freePort();
        int tlsB = freePort();
        Path output = dir.resolve("tls-out.txt");

        var serverRunner = SubprocessPsiEngineRunner.currentJvm(180, new ProtocolTlsProfile(
                dir.resolve("nodeA.p12"), dir.resolve("nodeA-trust.p12"), "changeit",
                tlsA, "127.0.0.1:" + tlsB));
        var clientRunner = SubprocessPsiEngineRunner.currentJvm(180, new ProtocolTlsProfile(
                dir.resolve("nodeB.p12"), dir.resolve("nodeB-trust.p12"), "changeit",
                tlsB, "127.0.0.1:" + tlsA));

        var serverFuture = CompletableFuture.supplyAsync(() -> serverRunner.run(
                job("server", nettyA, sets.partyA(), null, 5_000)));
        var clientResult = clientRunner.run(job("client", nettyB, sets.partyB(), output, 5_000));
        var serverResult = serverFuture.join();

        assertTrue(serverResult.success(), "server 引擎：" + serverResult.log());
        assertTrue(clientResult.success(), "client 引擎：" + clientResult.log());
        assertTrue(clientResult.log().contains("协议面 TLS 已启用"), "应走 TLS 分支");

        Set<Long> truth = Arrays.stream(sets.intersection()).boxed().collect(Collectors.toSet());
        Set<Long> actual;
        try (var lines = Files.lines(output)) {
            actual = lines.filter(l -> !l.isBlank()).map(Long::valueOf).collect(Collectors.toSet());
        }
        assertEquals(truth, actual, "TLS 隧道下交集必须与真值一致");
    }

    @Test
    void 错误信任库必须失败() throws Exception {
        var sets = IdSetGenerator.generate(7L, 1_000, 1_000, 0.5);
        int nettyA = freePort();
        int nettyB = freePort();
        int tlsA = freePort();
        int tlsB = freePort();

        var serverRunner = SubprocessPsiEngineRunner.currentJvm(15, new ProtocolTlsProfile(
                dir.resolve("nodeA.p12"), dir.resolve("nodeA-trust.p12"), "changeit",
                tlsA, "127.0.0.1:" + tlsB));
        // B 的信任库装的是 mallory 证书 → 拨号 A 时服务端证书校验必须失败
        var badClientRunner = SubprocessPsiEngineRunner.currentJvm(15, new ProtocolTlsProfile(
                dir.resolve("nodeB.p12"), dir.resolve("nodeB-badtrust.p12"), "changeit",
                tlsB, "127.0.0.1:" + tlsA));

        var serverFuture = CompletableFuture.supplyAsync(() -> serverRunner.run(
                job("server", nettyA, sets.partyA(), null, 1_000)));
        var clientResult = badClientRunner.run(
                job("client", nettyB, sets.partyB(), dir.resolve("bad-out.txt"), 1_000));
        serverFuture.join();   // server 侧超时/失败均可，不阻塞断言

        assertFalse(clientResult.success(), "错误信任库下 client 引擎必须失败");
        assertFalse(Files.exists(dir.resolve("bad-out.txt")), "失败时不得产出交集文件");
    }

    private EngineRunner.EngineJob job(String role, int nettyPort, long[] ids,
                                       Path output, int peerSize) {
        try {
            Path idsFile = Files.createTempFile("tls-ids", ".txt");
            StringBuilder sb = new StringBuilder();
            Arrays.stream(ids).forEach(id -> sb.append(id).append('\n'));
            Files.writeString(idsFile, sb);
            // TLS 模式下 peer 字段被引擎忽略（走 tlsPeer），置占位值
            return new EngineRunner.EngineJob(role, "rr22", "127.0.0.1:" + nettyPort,
                    "127.0.0.1:1", idsFile, output, ids.length, peerSize);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void trust(String storeName, String certAlias) throws Exception {
        exec("keytool", "-importcert", "-noprompt", "-alias", certAlias,
                "-keystore", dir.resolve(storeName + ".p12").toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", dir.resolve(certAlias + ".cer").toString());
    }

    private static void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "keytool 超时");
        assertEquals(0, p.exitValue(), "keytool 失败：" + out);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
