package com.ppcdemo.engine.psi;

import com.ppcdemo.platform.core.psi.SubprocessPsiEngineRunner;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.negotiation.NegotiationService;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.psi.PsiTaskOrchestrator;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 终验（CP2）：双节点（两套独立 DB/审计）+ 双 psi-engine 独立进程 + netty 协议面，
 * 协商→审批→执行→出库登记全链路；CARDINALITY_ONLY 时明细销毁。
 */
class DualNodePsiTaskTest {

    /** 一个节点 = 独立 DB + 领域服务组 */
    private static final class Node {
        final Database db;
        final TaskRepository tasks;
        final AuditCenter audit;
        final NegotiationService negotiation;
        final PsiTaskOrchestrator orchestrator;

        Node(String party) {
            db = Database.inMemory("node-" + party + "-" + UUID.randomUUID());
            tasks = new TaskRepository(db);
            audit = new AuditCenter(db);
            negotiation = new NegotiationService(tasks, audit, party);
            orchestrator = new PsiTaskOrchestrator(db, tasks, audit,
                    SubprocessPsiEngineRunner.currentJvm(180), party);
        }
    }

    @Test
    void 双节点PSI任务_基数模式_明细销毁() throws Exception {
        Node nodeA = new Node("A");
        Node nodeB = new Node("B");
        var sets = IdSetGenerator.generate(20260801L, 10_000, 10_000, 0.5);

        String taskId = "psi-" + UUID.randomUUID().toString().substring(0, 8);
        Path outputFile = Files.createTempDirectory("psi-e2e").resolve("out-" + taskId + ".txt");
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.PSI, "A", "B",
                "ds://A/customers", "ds://B/orders", "rr22",
                TaskContract.OutputGranularity.CARDINALITY_ONLY, null, null, false, null);

        // 协商：A 发起，进程内通道直连 B 的协商服务（mTLS 通道行为已在 MtlsChannelTest 门禁）
        PeerChannel loopback = c -> nodeB.negotiation.receiveProposal(c,
                any -> PeerChannel.Decision.approve());
        assertEquals(TaskState.READY, nodeA.negotiation.initiate(contract, loopback));
        assertEquals(TaskState.READY, nodeB.tasks.find(taskId).orElseThrow().state());

        // 执行：双方各自拉起本方 psi-engine 进程（B=server 持数据，A=client 得交集）
        int portB = freePort();
        int portA = freePort();
        var serverJob = new EngineRunner.EngineJob("server", "rr22",
                "127.0.0.1:" + portB, "127.0.0.1:" + portA, null, null, 0, 10_000);
        var clientJob = new EngineRunner.EngineJob("client", "rr22",
                "127.0.0.1:" + portA, "127.0.0.1:" + portB, null, outputFile, 0, 10_000);

        CompletableFuture<Long> serverSide = CompletableFuture.supplyAsync(() ->
                nodeB.orchestrator.run(taskId, "server", sets.partyB(), serverJob));
        long cardinality = nodeA.orchestrator.run(taskId, "client", sets.partyA(), clientJob);
        serverSide.join();

        // 结果与真值
        assertEquals(5_000, cardinality, "交集基数必须与构造真值一致");
        assertEquals(TaskState.SUCCEEDED, nodeA.tasks.find(taskId).orElseThrow().state());
        assertEquals(TaskState.SUCCEEDED, nodeB.tasks.find(taskId).orElseThrow().state());
        assertFalse(Files.exists(outputFile), "CARDINALITY_ONLY 明细文件必须已销毁");

        // 双方审计链完整且关键事件齐全
        assertTrue(nodeA.audit.verifyChain());
        assertTrue(nodeB.audit.verifyChain());
        assertTrue(nodeA.audit.byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("DETAIL_DESTROYED")));
        assertTrue(nodeB.audit.byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("PSI_COMPLETED")));
    }

    @Test
    void 响应方拒绝则双方任务均REJECTED() {
        Node nodeA = new Node("A");
        Node nodeB = new Node("B");
        String taskId = "psi-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.PSI, "A", "B",
                "ds://A/x", "ds://B/y", "rr22",
                TaskContract.OutputGranularity.INTERSECTION_DETAIL, null, null, false, null);

        PeerChannel loopback = c -> nodeB.negotiation.receiveProposal(c,
                any -> PeerChannel.Decision.reject("数据集未获合规放行"));
        assertEquals(TaskState.REJECTED, nodeA.negotiation.initiate(contract, loopback));
        assertEquals(TaskState.REJECTED, nodeB.tasks.find(taskId).orElseThrow().state());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
