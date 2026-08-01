package com.ppcdemo.engine.psi;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.keyvault.AesGcmEnvelope;
import com.ppcdemo.platform.core.pir.PirTaskOrchestrator;
import com.ppcdemo.platform.core.pir.QueryQuotaCenter;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.psi.SubprocessPsiEngineRunner;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import com.ppcdemo.platform.gateway.QueryGateway;
import com.ppcdemo.platform.gateway.api.PpsRequest;
import com.ppcdemo.platform.gateway.auth.AppAuthenticator;
import com.ppcdemo.platform.gateway.auth.AppRegistry;
import com.ppcdemo.platform.gateway.auth.HmacSigner;
import com.ppcdemo.platform.gateway.catalog.CapabilityCatalog;
import com.ppcdemo.platform.gateway.exec.SyncQueryExecutor;
import com.ppcdemo.platform.gateway.idem.IdempotencyStore;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-P1 活体门禁：业务系统签名请求 → Gateway（认证/幂等/应用配额/appId 审计）
 * → 真实 PIR 匿踪查询（经 PirTaskOrchestrator，数据集配额与任务审计在治理内核强制）→ 真值。
 * 证明服务化外壳串通真实隐私计算，且不绕治理内核。
 */
class GatewayPirIntegrationTest {

    private static final int DB_SIZE = 1_000;
    private static final int VALUE_BITS = 128;

    @Test
    void 业务系统经Gateway发起真实匿踪查询() throws Exception {
        // ── 征信方 B：持信用库 + 应答编排 ──
        Database bureauDb = Database.inMemory("bureau-" + UUID.randomUUID());
        TaskRepository bureauTasks = new TaskRepository(bureauDb);
        AuditCenter bureauAudit = new AuditCenter(bureauDb);
        PirTaskOrchestrator bureauPir = new PirTaskOrchestrator(bureauDb, bureauTasks, bureauAudit,
                new QueryQuotaCenter(bureauDb), SubprocessPsiEngineRunner.currentJvm(180), "B-bureau");
        Path creditDb = Files.createTempDirectory("bureau").resolve("credit.csv");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DB_SIZE; i++) {
            sb.append("user-").append(i).append(",score-").append(600 + i % 300).append('\n');
        }
        Files.writeString(creditDb, sb);

        // ── 银行 A：Gateway 接入层 + 查询方编排 ──
        Database bankDb = Database.inMemory("bank-" + UUID.randomUUID());
        byte[] masterKey = new byte[32];
        new java.security.SecureRandom().nextBytes(masterKey);
        AppRegistry apps = new AppRegistry(bankDb, new AesGcmEnvelope(masterKey));
        AuditCenter gwAudit = new AuditCenter(bankDb);
        TaskRepository bankTasks = new TaskRepository(bankDb);
        QueryQuotaCenter bankDatasetQuota = new QueryQuotaCenter(bankDb);
        PirTaskOrchestrator querierPir = new PirTaskOrchestrator(bankDb, bankTasks, gwAudit,
                bankDatasetQuota, SubprocessPsiEngineRunner.currentJvm(180), "A-bank");

        Path catalogFile = Files.createTempFile("caps", ".yaml");
        Files.writeString(catalogFile, """
                capabilities:
                  - id: credit-check
                    displayName: "信用核验"
                    mode: SYNC_QUERY
                    taskType: PIR_QUERY
                    protocol: pai_cks
                    partner: B-bureau
                    dataset: csv://B/credit-scores
                    outputGranularity: INTERSECTION_DETAIL
                    valueBits: 128
                    quota: { datasetDaily: 100000, perAppDaily: 20 }
                """);
        CapabilityCatalog catalog = CapabilityCatalog.fromYaml(catalogFile);

        AtomicReference<String> lastPirTask = new AtomicReference<>();

        // 真实 PIR 执行器：单键查询 → 两方 PIR（querier 经 orchestrator，治理内核强制数据集配额+任务审计）
        SyncQueryExecutor executor = (cap, key) -> {
            try {
                String taskId = "pir-" + UUID.randomUUID().toString().substring(0, 8);
                lastPirTask.set(taskId);
                TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.PSI,
                        "A-bank", "B-bureau", "local://A/q", cap.dataset(), cap.protocol(),
                        TaskContract.OutputGranularity.INTERSECTION_DETAIL, null, null, false, null);
                bankTasks.insert(contract, TaskState.READY);
                bureauTasks.insert(contract, TaskState.READY);
                bankDatasetQuota.ensureQuota(cap.dataset(), 100_000);

                int portServer = freePort();
                int portClient = freePort();
                Path out = Files.createTempDirectory("pir-out").resolve("r.txt");
                Map<String, String> extras = Map.of("protocolFamily", "pir",
                        "valueBits", String.valueOf(VALUE_BITS), "databaseSize", String.valueOf(DB_SIZE));
                var serverJob = new EngineRunner.EngineJob("server", cap.protocol(),
                        "127.0.0.1:" + portServer, "127.0.0.1:" + portClient, creditDb, null,
                        DB_SIZE, 1, extras);
                var clientJob = new EngineRunner.EngineJob("client", cap.protocol(),
                        "127.0.0.1:" + portClient, "127.0.0.1:" + portServer, null, out, 1, DB_SIZE, extras);

                CompletableFuture<Void> bureauSide = CompletableFuture.runAsync(() ->
                        bureauPir.runResponder(taskId, creditDb, serverJob));
                var outcome = querierPir.runQuerier(taskId, List.of(key), clientJob);
                bureauSide.join();

                String value = outcome.values().isEmpty() ? null : outcome.values().get(0);
                return value == null || value.isBlank()
                        ? new SyncQueryExecutor.QueryResult(false, null)
                        : new SyncQueryExecutor.QueryResult(true, value);
            } catch (Exception e) {
                throw new IllegalStateException("PIR 执行失败", e);
            }
        };

        QueryGateway gateway = new QueryGateway(bankDb,
                new AppAuthenticator(apps, 60_000), apps, catalog, new IdempotencyStore(bankDb),
                new AppDailyQuota(bankDb), gwAudit, executor, "A-bank", System::currentTimeMillis);
        apps.register("risk-system", "风控系统", "sekret");
        apps.grant("risk-system", "credit-check", 20);

        // ── 业务系统发起：命中查询 ──
        var resp = gateway.handle(sign("risk-system", "sekret", "credit-check", "loan-001", "user-7"));
        assertTrue(resp.hit());
        assertEquals("score-" + (600 + 7 % 300), resp.value(), "应返回征信库中 user-7 的真实分值");
        assertFalse(resp.replayed());

        // ── 幂等：同 reqId 重放返回原值、不再执行 PIR ──
        String pirTaskAfterFirst = lastPirTask.get();
        var replay = gateway.handle(sign("risk-system", "sekret", "credit-check", "loan-001", "user-7"));
        assertTrue(replay.replayed());
        assertEquals(resp.value(), replay.value());
        assertEquals(pirTaskAfterFirst, lastPirTask.get(), "重放不得再触发 PIR 执行");

        // ── 治理证据 ──
        assertTrue(gwAudit.verifyChain());
        String bankAudit = gwAudit.byTask("loan-001").stream()
                .map(e -> e.eventType() + String.valueOf(e.payload())).reduce("", String::concat);
        assertTrue(bankAudit.contains("risk-system"), "接入审计须含 appId");
        assertFalse(bankAudit.contains("user-7"), "审计绝不含查询键");
        assertTrue(bureauAudit.byTask(pirTaskAfterFirst).stream()
                        .anyMatch(e -> e.eventType().equals("PIR_SERVED")
                                && e.payload().contains("queryContentVisible\": false")),
                "征信方审计须记录其无从得知查询内容");

        System.out.println("M5-P1 活体通过：业务系统 → Gateway → 真实 PIR，值=" + resp.value());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static PpsRequest sign(String appId, String secret, String cap, String reqId, String key) {
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String canonical = HmacSigner.canonical(appId, ts, nonce, cap, reqId, key);
        return new PpsRequest(appId, cap, reqId, ts, nonce, key, HmacSigner.sign(secret, canonical));
    }
}
