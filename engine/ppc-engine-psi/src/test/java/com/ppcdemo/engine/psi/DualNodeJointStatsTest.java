package com.ppcdemo.engine.psi;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.common.datagen.ValueGenerator;
import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.data.DataChannel;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.jointstats.JointStatsOrchestrator;
import com.ppcdemo.platform.core.keyvault.EphemeralKeyVault;
import com.ppcdemo.platform.core.negotiation.NegotiationService;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.release.ReleaseGateway;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 终验（CP3）：双节点联合统计 DAG——M1-E2E 场景的平台化复现，且披露更小：
 * A（发起方/PSI server）不接触交集明细，只得加噪出库值；B（数据方/PSI client）交集即用即毁。
 */
class DualNodeJointStatsTest {

    private static final class Node {
        final Database db;
        final TaskRepository tasks;
        final AuditCenter audit;
        final BudgetCenter budget;
        final NegotiationService negotiation;
        final JointStatsOrchestrator joint;
        final ReleaseGateway gateway;
        final EphemeralKeyVault vault = new EphemeralKeyVault();

        Node(String party) {
            db = Database.inMemory("joint-" + party + "-" + UUID.randomUUID());
            tasks = new TaskRepository(db);
            audit = new AuditCenter(db);
            budget = new BudgetCenter(db);
            gateway = new ReleaseGateway(db, budget, audit);
            negotiation = new NegotiationService(tasks, audit, party);
            joint = new JointStatsOrchestrator(tasks, audit, party);
        }
    }

    @Test
    void 双节点联合统计端到端() throws Exception {
        Node nodeA = new Node("A");
        Node nodeB = new Node("B");
        nodeA.budget.ensureAccount("ds://A/customers", 10.0);

        int size = 10_000;
        long clampUpper = 100_000;
        var sets = IdSetGenerator.generate(20260801L, size, size, 0.5);
        long[] amounts = ValueGenerator.logNormalCents(1L, size, clampUpper);
        Map<Long, Long> bAmounts = new HashMap<>();
        for (int i = 0; i < size; i++) {
            bAmounts.put(sets.partyB()[i], amounts[i]);
        }

        String taskId = "joint-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.JOINT_STATS,
                "A", "B", "ds://A/customers", "ds://B/orders", "rr22",
                TaskContract.OutputGranularity.NOISED_SCALAR, 1.0, clampUpper, false, null);

        PeerChannel loopback = c -> nodeB.negotiation.receiveProposal(c,
                any -> PeerChannel.Decision.approve());
        assertEquals(TaskState.READY, nodeA.negotiation.initiate(contract, loopback));

        int portA = freePort();
        int portB = freePort();
        Path outputFile = Files.createTempDirectory("joint-e2e").resolve("i-" + taskId + ".txt");
        var serverJob = new EngineRunner.EngineJob("server", "rr22",
                "127.0.0.1:" + portA, "127.0.0.1:" + portB, null, null, 0, size);
        var clientJob = new EngineRunner.EngineJob("client", "rr22",
                "127.0.0.1:" + portB, "127.0.0.1:" + portA, null, outputFile, 0, size);

        DataChannel channel = DataChannel.inProcess();
        EngineRunner engine = SubprocessPsiEngineRunner.currentJvm(180);

        CompletableFuture<Void> responderSide = CompletableFuture.runAsync(() ->
                nodeB.joint.runResponder(taskId, sets.partyB(), bAmounts, engine, clientJob, channel, 120));
        double released = nodeA.joint.runInitiator(taskId, sets.partyA(), engine, serverJob,
                channel, nodeA.vault, nodeA.gateway, 120);
        responderSide.join();

        // 真值对照（仅测试程序有权计算）
        long expected = java.util.Arrays.stream(sets.intersection()).map(bAmounts::get).sum();
        assertTrue(Math.abs(released - expected) < 20 * clampUpper,
                "出库值偏差超噪声界：released=%s expected=%d".formatted(released, expected));

        assertEquals(TaskState.SUCCEEDED, nodeA.tasks.find(taskId).orElseThrow().state());
        assertEquals(TaskState.SUCCEEDED, nodeB.tasks.find(taskId).orElseThrow().state());
        assertEquals(released, nodeA.gateway.releasedValue(taskId).orElseThrow(), 1e-9);
        assertEquals(9.0, nodeA.budget.remaining("ds://A/customers"), 1e-9, "ε=1 应已扣减");
        assertFalse(Files.exists(outputFile), "B 方交集明细必须即用即毁");

        // 披露控制：A 方审计不得出现交集基数之外的明细痕迹；密钥已销毁
        assertTrue(nodeA.audit.verifyChain());
        assertTrue(nodeB.audit.verifyChain());
        assertTrue(nodeA.audit.byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("CIPHER_SUM_DECRYPTED")));
        assertTrue(nodeB.audit.byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("INTERSECTION_USED_AND_DESTROYED")));
        assertThrows_keyDestroyed(nodeA, taskId);
    }

    private static void assertThrows_keyDestroyed(Node nodeA, String taskId) {
        try {
            nodeA.vault.decrypt(taskId, new byte[8]);
            throw new AssertionError("任务结束后密钥应已销毁");
        } catch (IllegalStateException expected) {
            // 预期：密钥不存在
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
