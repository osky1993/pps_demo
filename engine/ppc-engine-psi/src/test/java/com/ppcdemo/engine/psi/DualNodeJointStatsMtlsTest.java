package com.ppcdemo.engine.psi;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.common.datagen.ValueGenerator;
import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.data.BlockingDataStore;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.jointstats.JointStatsOrchestrator;
import com.ppcdemo.platform.core.keyvault.EphemeralKeyVault;
import com.ppcdemo.platform.core.negotiation.NegotiationService;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.ppcdemo.platform.core.net.MtlsDataChannel;
import com.ppcdemo.platform.core.net.MtlsNegotiationServer;
import com.ppcdemo.platform.core.net.MtlsPeerChannel;
import com.ppcdemo.platform.core.net.TlsContexts;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.release.ReleaseGateway;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2.5 #2 终验：跨节点联合统计——协商与数据通道（公钥/密文Σ）全部经真实 mTLS HTTPS，
 * 两节点仅共享网络端口（无共享内存对象），即双机部署的单机等效验证。
 */
class DualNodeJointStatsMtlsTest {

    @TempDir
    static Path certDir;

    static SSLContext sslA;
    static SSLContext sslB;
    static MtlsNegotiationServer serverA;
    static MtlsNegotiationServer serverB;
    static BlockingDataStore storeA;
    static BlockingDataStore storeB;

    // 节点服务组
    static Node nodeA;
    static Node nodeB;

    private record Node(Database db, TaskRepository tasks, AuditCenter audit, BudgetCenter budget,
                        NegotiationService negotiation, JointStatsOrchestrator joint,
                        ReleaseGateway gateway, EphemeralKeyVault vault) {
        static Node of(String party) {
            Database db = Database.inMemory("mtls-joint-" + party + "-" + UUID.randomUUID());
            TaskRepository tasks = new TaskRepository(db);
            AuditCenter audit = new AuditCenter(db);
            BudgetCenter budget = new BudgetCenter(db);
            return new Node(db, tasks, audit, budget,
                    new NegotiationService(tasks, audit, party),
                    new JointStatsOrchestrator(tasks, audit, party),
                    new ReleaseGateway(db, budget, audit), new EphemeralKeyVault());
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        genKeyPair("nodeA");
        genKeyPair("nodeB");
        trust("nodeA", "nodeB");
        trust("nodeB", "nodeA");
        sslA = TlsContexts.build(certDir.resolve("nodeA.p12"), "changeit".toCharArray(),
                certDir.resolve("nodeA-trust.p12"));
        sslB = TlsContexts.build(certDir.resolve("nodeB.p12"), "changeit".toCharArray(),
                certDir.resolve("nodeB-trust.p12"));

        nodeA = Node.of("A");
        nodeB = Node.of("B");
        storeA = new BlockingDataStore();
        storeB = new BlockingDataStore();
        // 双方各自的控制面服务：B 侧挂真实审批策略，A 侧仅收数据（发起方不受理提议）
        serverB = new MtlsNegotiationServer(0, sslB,
                contract -> nodeB.negotiation().receiveProposal(contract,
                        any -> PeerChannel.Decision.approve()), storeB);
        serverA = new MtlsNegotiationServer(0, sslA,
                contract -> PeerChannel.Decision.reject("发起方节点不受理提议"), storeA);
    }

    @AfterAll
    static void teardown() {
        if (serverA != null) {
            serverA.close();
        }
        if (serverB != null) {
            serverB.close();
        }
    }

    @Test
    void 跨节点mTLS联合统计端到端() throws Exception {
        nodeA.budget().ensureAccount("ds://A/customers", 10.0);
        int size = 10_000;
        long clampUpper = 100_000;
        var sets = IdSetGenerator.generate(20260801L, size, size, 0.5);
        long[] amounts = ValueGenerator.logNormalCents(1L, size, clampUpper);
        Map<Long, Long> bAmounts = new HashMap<>();
        for (int i = 0; i < size; i++) {
            bAmounts.put(sets.partyB()[i], amounts[i]);
        }

        String taskId = "mtls-joint-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.JOINT_STATS,
                "A", "B", "ds://A/customers", "ds://B/orders", "rr22",
                TaskContract.OutputGranularity.NOISED_SCALAR, 1.0, clampUpper, false, null);

        // 协商走真实 mTLS HTTPS
        var negotiationChannel = new MtlsPeerChannel("https://localhost:" + serverB.port(), sslA);
        assertEquals(TaskState.READY, nodeA.negotiation().initiate(contract, negotiationChannel));
        assertEquals(TaskState.READY, nodeB.tasks().find(taskId).orElseThrow().state());

        // 数据通道走真实 mTLS HTTPS：各方 put 推对方、take 取本地
        var channelA = new MtlsDataChannel("https://localhost:" + serverB.port(), sslA, storeA);
        var channelB = new MtlsDataChannel("https://localhost:" + serverA.port(), sslB, storeB);

        int portA = freePort();
        int portB = freePort();
        Path outputFile = Files.createTempDirectory("mtls-joint").resolve("i.txt");
        var serverJob = new EngineRunner.EngineJob("server", "rr22",
                "127.0.0.1:" + portA, "127.0.0.1:" + portB, null, null, 0, size);
        var clientJob = new EngineRunner.EngineJob("client", "rr22",
                "127.0.0.1:" + portB, "127.0.0.1:" + portA, null, outputFile, 0, size);
        EngineRunner engine = SubprocessPsiEngineRunner.currentJvm(180);

        CompletableFuture<Void> bSide = CompletableFuture.runAsync(() -> nodeB.joint()
                .runResponder(taskId, sets.partyB(), bAmounts, engine, clientJob, channelB, 120));
        double released = nodeA.joint().runInitiator(taskId, sets.partyA(), engine, serverJob,
                channelA, nodeA.vault(), nodeA.gateway(), 120);
        bSide.join();

        long expected = java.util.Arrays.stream(sets.intersection()).map(bAmounts::get).sum();
        assertTrue(Math.abs(released - expected) < 20 * clampUpper,
                "出库值偏差超噪声界：released=%s expected=%d".formatted(released, expected));
        assertEquals(TaskState.SUCCEEDED, nodeA.tasks().find(taskId).orElseThrow().state());
        assertEquals(TaskState.SUCCEEDED, nodeB.tasks().find(taskId).orElseThrow().state());
        assertEquals(9.0, nodeA.budget().remaining("ds://A/customers"), 1e-9);
        assertTrue(nodeA.audit().verifyChain());
        assertTrue(nodeB.audit().verifyChain());
    }

    private static void genKeyPair(String name) throws Exception {
        exec("keytool", "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", certDir.resolve(name + ".p12").toString(),
                "-storepass", "changeit", "-dname", "CN=" + name,
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "30");
        exec("keytool", "-exportcert", "-alias", name,
                "-keystore", certDir.resolve(name + ".p12").toString(), "-storepass", "changeit",
                "-file", certDir.resolve(name + ".cer").toString());
    }

    private static void trust(String owner, String trusted) throws Exception {
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
