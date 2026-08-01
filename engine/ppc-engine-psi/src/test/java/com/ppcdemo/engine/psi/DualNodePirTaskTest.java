package com.ppcdemo.engine.psi;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.negotiation.NegotiationService;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.ppcdemo.platform.core.pir.PirTaskOrchestrator;
import com.ppcdemo.platform.core.pir.QueryQuotaCenter;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.psi.SubprocessPsiEngineRunner;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 终验：双节点匿踪查询端到端——银行 A 向征信方 B 查信用分，
 * B 应答但无从得知查了谁；配额治理与「审计不含 key」双重验证。
 */
class DualNodePirTaskTest {

    private static final int DB_SIZE = 2_000;
    private static final int VALUE_BITS = 128;

    private record Node(Database db, TaskRepository tasks, AuditCenter audit,
                        QueryQuotaCenter quotas, NegotiationService negotiation,
                        PirTaskOrchestrator pir) {
        static Node of(String party) {
            Database db = Database.inMemory("pir-" + party + "-" + UUID.randomUUID());
            TaskRepository tasks = new TaskRepository(db);
            AuditCenter audit = new AuditCenter(db);
            QueryQuotaCenter quotas = new QueryQuotaCenter(db);
            return new Node(db, tasks, audit, quotas,
                    new NegotiationService(tasks, audit, party),
                    new PirTaskOrchestrator(db, tasks, audit, quotas,
                            SubprocessPsiEngineRunner.currentJvm(180), party));
        }
    }

    @Test
    void 双节点匿踪查询端到端_含配额与审计克制() throws Exception {
        Node bank = Node.of("A-bank");        // 查询方（发起方）
        Node bureau = Node.of("B-bureau");    // 数据方（响应方）
        String creditDataset = "csv://B/credit-scores";
        bank.quotas().ensureQuota(creditDataset, 10);   // 全库 2000 条，只允许查 10 条

        // 数据方的库：user-0..1999 → 信用分
        Path workDir = Files.createTempDirectory("pir-e2e");
        Path dbFile = workDir.resolve("credit.csv");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DB_SIZE; i++) {
            sb.append("user-").append(i).append(",score-").append(600 + i % 300).append('\n');
        }
        Files.writeString(dbFile, sb);

        // 1. 协商
        String taskId = "pir-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.PSI,
                "A-bank", "B-bureau", "local://A/queries", creditDataset, "pai_cks",
                TaskContract.OutputGranularity.INTERSECTION_DETAIL, null, null, false, null);
        assertEquals(TaskState.READY, bank.negotiation().initiate(contract,
                c -> bureau.negotiation().receiveProposal(c, any -> PeerChannel.Decision.approve())));

        // 2. 执行：A 查 3 个键（其中 1 个不存在），B 应答
        List<String> queries = List.of("user-7", "user-1500", "不存在的人");
        int portServer = freePort();
        int portClient = freePort();
        Path output = workDir.resolve("result-" + taskId + ".txt");
        Map<String, String> pirExtras = Map.of(
                "protocolFamily", "pir",
                "valueBits", String.valueOf(VALUE_BITS),
                "databaseSize", String.valueOf(DB_SIZE));

        var serverJob = new EngineRunner.EngineJob("server", "pai_cks",
                "127.0.0.1:" + portServer, "127.0.0.1:" + portClient, dbFile, null,
                DB_SIZE, queries.size(), pirExtras);
        var clientJob = new EngineRunner.EngineJob("client", "pai_cks",
                "127.0.0.1:" + portClient, "127.0.0.1:" + portServer, null, output,
                queries.size(), DB_SIZE, pirExtras);

        CompletableFuture<Void> bureauSide = CompletableFuture.runAsync(() ->
                bureau.pir().runResponder(taskId, dbFile, serverJob));
        var outcome = bank.pir().runQuerier(taskId, queries, clientJob);
        bureauSide.join();

        // 3. 结果正确性
        assertEquals(3, outcome.values().size());
        assertEquals("score-" + (600 + 7 % 300), outcome.values().get(0));
        assertEquals("score-" + (600 + 1500 % 300), outcome.values().get(1));
        assertEquals(2, outcome.hitCount(), "命中 2 条、未命中 1 条");
        assertEquals(TaskState.SUCCEEDED, bank.tasks().find(taskId).orElseThrow().state());
        assertEquals(TaskState.SUCCEEDED, bureau.tasks().find(taskId).orElseThrow().state());

        // 4. 配额已扣 3 条
        assertEquals(7.0, bank.quotas().remaining(creditDataset), 1e-9);

        // 5. 审计的主动克制：记录条数与命中，绝不记录查了哪些 key
        assertTrue(bank.audit().verifyChain());
        assertTrue(bureau.audit().verifyChain());
        String bankAudit = bank.audit().byTask(taskId).stream()
                .map(e -> e.eventType() + String.valueOf(e.payload()))
                .reduce("", String::concat);
        for (String key : queries) {
            assertFalse(bankAudit.contains(key), "审计不得出现查询键：" + key);
        }
        assertTrue(bankAudit.contains("PIR_QUOTA_CONSUMED") && bankAudit.contains("PIR_COMPLETED"));
        // 数据方审计如实反映其无知
        assertTrue(bureau.audit().byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("PIR_SERVED")
                        && e.payload().contains("\"queryContentVisible\": false")));
        // 查询键文件即用即毁
        assertFalse(Files.exists(output), "结果文件读后即毁");

        System.out.printf("匿踪查询端到端：库 %d 条，查 %d 条，命中 %d，%d ms%n",
                DB_SIZE, queries.size(), outcome.hitCount(), outcome.elapsedMillis());
    }

    @Test
    void 配额耗尽熔断且不扣减() {
        Node bank = Node.of("A-bank");
        String dataset = "csv://B/credit";
        bank.quotas().ensureQuota(dataset, 5);
        bank.quotas().consume(dataset, 4);

        assertThrows(QueryQuotaCenter.QuotaExhaustedException.class,
                () -> bank.quotas().consume(dataset, 3), "超额申请必须熔断");
        assertEquals(1.0, bank.quotas().remaining(dataset), 1e-9, "失败不扣减");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
