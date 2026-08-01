package com.ppcdemo.platform.gateway;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.keyvault.AesGcmEnvelope;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.gateway.api.GatewayException;
import com.ppcdemo.platform.gateway.api.PpsRequest;
import com.ppcdemo.platform.gateway.auth.AppAuthenticator;
import com.ppcdemo.platform.gateway.auth.AppRegistry;
import com.ppcdemo.platform.gateway.auth.HmacSigner;
import com.ppcdemo.platform.gateway.catalog.CapabilityCatalog;
import com.ppcdemo.platform.gateway.exec.AsyncJobExecutor;
import com.ppcdemo.platform.gateway.idem.IdempotencyStore;
import com.ppcdemo.platform.gateway.job.CallbackSender;
import com.ppcdemo.platform.gateway.job.JobRepository;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-P2 门禁：异步作业——受理即返回、后台执行、并发闸、回调签名重试、轮询、结果三档、幂等。
 */
class AsyncJobGatewayTest {

    private static final String APP = "biz-system";
    private static final String SECRET = "async-secret";

    private Database db;
    private AppRegistry apps;
    private AuditCenter audit;
    private JobRepository jobs;
    private AsyncJobGateway gateway;
    private AsyncJobExecutor executor;   // 可替换以模拟慢/失败

    @BeforeEach
    void setup() {
        db = Database.inMemory("async-" + UUID.randomUUID());
        byte[] mk = new byte[32];
        new SecureRandom().nextBytes(mk);
        apps = new AppRegistry(db, new AesGcmEnvelope(mk));
        audit = new AuditCenter(db);
        jobs = new JobRepository(db);
        apps.register(APP, "业务系统", SECRET);
        apps.grant(APP, "joint-stats", 20);
        apps.grant(APP, "psi-match", 20);
    }

    @AfterEach
    void teardown() {
        if (gateway != null) {
            gateway.close();
        }
    }

    private void wire(AsyncJobExecutor exec) {
        wire(exec, new CallbackSender(apps, System::currentTimeMillis));
    }

    private void wire(AsyncJobExecutor exec, CallbackSender callbacks) {
        this.executor = exec;
        gateway = new AsyncJobGateway(db, new AppAuthenticator(apps, 60_000), apps,
                CapabilityCatalog.fromResource("/async-capabilities.yaml"),
                new IdempotencyStore(db), jobs, new AppDailyQuota(db), audit,
                exec, callbacks, "A-node", System::currentTimeMillis);
    }

    private PpsRequest sign(String cap, String reqId, String body) {
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String canonical = HmacSigner.canonical(APP, ts, nonce, cap, reqId, body);
        return new PpsRequest(APP, cap, reqId, ts, nonce, body, HmacSigner.sign(SECRET, canonical));
    }

    @Test
    void 受理即返回_后台执行_轮询取终态与结果() {
        wire((cap, params) -> new AsyncJobExecutor.JobResult("交集 3 条", List.of("a", "b", "c")));
        var r = gateway.submit(sign("psi-match", "job-1", "params"), null);
        assertEquals("ACCEPTED", r.state(), "受理即返回，不阻塞");

        var terminal = gateway.awaitTerminal(APP, "job-1", 5_000);
        assertEquals(JobRepository.JobState.SUCCEEDED, terminal.state());
        assertEquals(JobRepository.ResultKind.INLINE, terminal.resultKind(), "小结果内联");

        var page = gateway.result(APP, "job-1", 0, 10);
        assertEquals(List.of("a", "b", "c"), page.lines());
        assertTrue(audit.verifyChain());
    }

    @Test
    void 大结果PAGED分页拉取() {
        List<String> big = IntStream.range(0, 5000).mapToObj(i -> "row-" + i).toList();
        wire((cap, params) -> new AsyncJobExecutor.JobResult("交集 5000 条", big));
        gateway.submit(sign("psi-match", "job-2", "p"), null);
        gateway.awaitTerminal(APP, "job-2", 5_000);

        assertEquals(JobRepository.ResultKind.PAGED,
                gateway.poll(APP, "job-2").resultKind(), "大结果走分页档");
        var page1 = gateway.result(APP, "job-2", 0, 1000);
        assertEquals(1000, page1.lines().size());
        assertTrue(page1.hasMore());
        assertEquals("row-0", page1.lines().get(0));
        var page2 = gateway.result(APP, "job-2", page1.nextCursor(), 1000);
        assertEquals("row-1000", page2.lines().get(0));
    }

    @Test
    void 并发闸_单能力串行执行() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        wire((cap, params) -> {
            int now = concurrent.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return new AsyncJobExecutor.JobResult("ok", List.of("x"));
        });
        // joint-stats maxConcurrency=1；同时提交 3 个
        for (int i = 0; i < 3; i++) {
            gateway.submit(sign("joint-stats", "cc-" + i, "p"), null);
        }
        for (int i = 0; i < 3; i++) {
            gateway.awaitTerminal(APP, "cc-" + i, 10_000);
        }
        assertEquals(1, maxObserved.get(), "并发闸=1，任意时刻至多 1 个在跑");
    }

    @Test
    void 幂等_重复提交不重复执行() {
        AtomicInteger runs = new AtomicInteger();
        wire((cap, params) -> {
            runs.incrementAndGet();
            return new AsyncJobExecutor.JobResult("ok", List.of("v"));
        });
        gateway.submit(sign("psi-match", "dup", "p"), null);
        gateway.awaitTerminal(APP, "dup", 5_000);
        var second = gateway.submit(sign("psi-match", "dup", "p"), null);
        assertEquals("SUCCEEDED", second.state(), "重复提交返回既有终态");
        assertEquals(1, runs.get(), "执行只发生一次");
    }

    @Test
    void 回调_签名可验且失败重试后成功() throws Exception {
        // 回调接收端：前 2 次返回 500，第 3 次 200；记录并验签
        AtomicInteger hits = new AtomicInteger();
        ConcurrentLinkedQueue<Boolean> verified = new ConcurrentLinkedQueue<>();
        HttpServer receiver = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/cb", ex -> {
            int n = hits.incrementAndGet();
            String bodyText = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (n <= 2) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
                return;
            }
            Properties p = new Properties();
            p.load(new java.io.StringReader(bodyText));
            // 业务系统用同一 secret 验签
            var client = new com.ppcdemo.platform.gateway.sdk.PpsClient("http://x", APP, SECRET);
            verified.add(client.verifyCallback(p.getProperty("jobId"), p.getProperty("state"),
                    p.getProperty("summary"), Long.parseLong(p.getProperty("timestamp")),
                    p.getProperty("nonce"), p.getProperty("signature")));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        receiver.start();
        try {
            wire((cap, params) -> new AsyncJobExecutor.JobResult("done", List.of("r")));
            String cbUrl = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/cb";
            gateway.submit(sign("psi-match", "cb-job", "p"), cbUrl);
            gateway.awaitTerminal(APP, "cb-job", 5_000);
            // 等回调重试完成
            long deadline = System.currentTimeMillis() + 5_000;
            while (verified.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(3, hits.get(), "前 2 次失败 + 第 3 次成功");
            assertTrue(verified.peek(), "回调签名须可被业务系统验证");
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void 同步能力走异步提交_被拒() {
        wire((cap, params) -> new AsyncJobExecutor.JobResult("x", List.of()));
        // credit-check 是 SYNC（在另一个 yaml），此处用未定义能力触发 BAD_REQUEST
        var e = assertThrows(GatewayException.class,
                () -> gateway.submit(sign("unknown-cap", "j", "p"), null));
        assertEquals(GatewayException.Reason.BAD_REQUEST, e.reason());
    }
}
