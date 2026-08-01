package com.ppcdemo.engine.psi;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.keyvault.AesGcmEnvelope;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.psi.PsiTaskOrchestrator;
import com.ppcdemo.platform.core.psi.SubprocessPsiEngineRunner;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import com.ppcdemo.platform.gateway.AsyncJobGateway;
import com.ppcdemo.platform.gateway.auth.AppAuthenticator;
import com.ppcdemo.platform.gateway.auth.AppRegistry;
import com.ppcdemo.platform.gateway.catalog.CapabilityCatalog;
import com.ppcdemo.platform.gateway.exec.AsyncJobExecutor;
import com.ppcdemo.platform.gateway.http.PpsGatewayServer;
import com.ppcdemo.platform.gateway.idem.IdempotencyStore;
import com.ppcdemo.platform.gateway.job.CallbackSender;
import com.ppcdemo.platform.gateway.job.JobRepository;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;
import com.ppcdemo.platform.gateway.sdk.PpsClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-P2 活体门禁：业务系统经 SDK 提交异步 PSI 作业 → Gateway 受理即返回 → 后台真实 PSI 求交
 * → 回调（签名）→ 业务系统验签并取结果。证明异步机制串通真实隐私计算。
 */
class AsyncGatewayPsiIntegrationTest {

    @Test
    void 业务系统经SDK提交异步PSI作业并收到签名回调() throws Exception {
        // 数据方 B：持 ID 库，PSI server
        Database bDb = Database.inMemory("b-" + UUID.randomUUID());
        TaskRepository bTasks = new TaskRepository(bDb);
        AuditCenter bAudit = new AuditCenter(bDb);
        PsiTaskOrchestrator bPsi = new PsiTaskOrchestrator(bDb, bTasks, bAudit,
                SubprocessPsiEngineRunner.currentJvm(180), "B-shop");

        // 发起方 A：Gateway + PSI client
        Database aDb = Database.inMemory("a-" + UUID.randomUUID());
        byte[] mk = new byte[32];
        new SecureRandom().nextBytes(mk);
        AppRegistry apps = new AppRegistry(aDb, new AesGcmEnvelope(mk));
        AuditCenter gwAudit = new AuditCenter(aDb);
        TaskRepository aTasks = new TaskRepository(aDb);
        PsiTaskOrchestrator aPsi = new PsiTaskOrchestrator(aDb, aTasks, gwAudit,
                SubprocessPsiEngineRunner.currentJvm(180), "A-bank");

        var sets = IdSetGenerator.generate(20260801L, 10_000, 10_000, 0.5);

        Path catalog = Files.createTempFile("caps", ".yaml");
        Files.writeString(catalog, """
                capabilities:
                  - id: psi-match
                    displayName: "隐私求交"
                    mode: ASYNC_JOB
                    taskType: PSI
                    protocol: rr22
                    partner: B-shop
                    dataset: ds://B/ids
                    outputGranularity: CARDINALITY_ONLY
                    maxConcurrency: 2
                    quota: { datasetDaily: 100, perAppDaily: 10 }
                """);

        // 真实 PSI 执行器：一次求交返回交集基数
        AsyncJobExecutor executor = (cap, params) -> {
            try {
                String taskId = "psi-" + UUID.randomUUID().toString().substring(0, 8);
                TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.PSI,
                        "A-bank", "B-shop", "ds://A/ids", cap.dataset(), cap.protocol(),
                        TaskContract.OutputGranularity.CARDINALITY_ONLY, null, null, false, null);
                aTasks.insert(contract, TaskState.READY);
                bTasks.insert(contract, TaskState.READY);
                int pA = freePort();
                int pB = freePort();
                Path out = Files.createTempDirectory("psi").resolve("out.txt");
                var serverJob = new EngineRunner.EngineJob("server", "rr22",
                        "127.0.0.1:" + pB, "127.0.0.1:" + pA, null, null, 0, 10_000);
                var clientJob = new EngineRunner.EngineJob("client", "rr22",
                        "127.0.0.1:" + pA, "127.0.0.1:" + pB, null, out, 0, 10_000);
                CompletableFuture<Long> serverSide = CompletableFuture.supplyAsync(() ->
                        bPsi.run(taskId, "server", sets.partyB(), serverJob));
                long cardinality = aPsi.run(taskId, "client", sets.partyA(), clientJob);
                serverSide.join();
                return new AsyncJobExecutor.JobResult("交集 " + cardinality + " 条",
                        List.of(String.valueOf(cardinality)));
            } catch (Exception e) {
                throw new IllegalStateException("PSI 作业失败", e);
            }
        };

        AsyncJobGateway async = new AsyncJobGateway(aDb, new AppAuthenticator(apps, 60_000), apps,
                CapabilityCatalog.fromYaml(catalog), new IdempotencyStore(aDb), new JobRepository(aDb),
                new AppDailyQuota(aDb), gwAudit, executor,
                new CallbackSender(apps, System::currentTimeMillis), "A-bank", System::currentTimeMillis);
        apps.register("risk-system", "风控系统", "sekret");
        apps.grant("risk-system", "psi-match", 10);

        PpsGatewayServer server = new PpsGatewayServer("127.0.0.1", 0, null, async);

        // 回调接收端（业务系统侧）
        AtomicReference<Boolean> callbackVerified = new AtomicReference<>();
        AtomicReference<String> callbackSummary = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        PpsClient verifier = new PpsClient("http://x", "risk-system", "sekret");
        receiver.createContext("/cb", ex -> {
            Properties p = new Properties();
            p.load(new java.io.StringReader(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            callbackVerified.set(verifier.verifyCallback(p.getProperty("jobId"), p.getProperty("state"),
                    p.getProperty("summary"), Long.parseLong(p.getProperty("timestamp")),
                    p.getProperty("nonce"), p.getProperty("signature")));
            callbackSummary.set(p.getProperty("summary"));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        receiver.start();

        try {
            PpsClient client = new PpsClient("http://127.0.0.1:" + server.port(), "risk-system", "sekret");
            String cbUrl = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/cb";

            var handle = client.submit("psi-match", "match-req", "biz-batch-01", cbUrl);
            assertEquals("ACCEPTED", handle.state(), "受理即返回");

            // 轮询直到终态
            String state = "";
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                state = client.getJob("biz-batch-01").state();
                if (state.equals("SUCCEEDED") || state.equals("FAILED")) {
                    break;
                }
                Thread.sleep(200);
            }
            assertEquals("SUCCEEDED", state);

            var result = client.getResult("biz-batch-01", 0, 10);
            assertEquals("5000", result.get("lines"), "交集基数 = 构造真值 5000");

            // 回调已到达且签名可验
            long cbDeadline = System.currentTimeMillis() + 5_000;
            while (callbackVerified.get() == null && System.currentTimeMillis() < cbDeadline) {
                Thread.sleep(50);
            }
            assertTrue(callbackVerified.get(), "回调签名须可被业务系统验证");
            assertTrue(callbackSummary.get().contains("5000"));
            System.out.println("M5-P2 活体通过：异步 PSI 作业 + 签名回调，" + callbackSummary.get());
        } finally {
            receiver.stop(0);
            server.close();
            async.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
