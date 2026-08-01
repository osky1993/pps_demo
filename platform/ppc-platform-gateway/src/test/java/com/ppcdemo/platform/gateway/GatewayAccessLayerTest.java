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
import com.ppcdemo.platform.gateway.exec.SyncQueryExecutor;
import com.ppcdemo.platform.gateway.idem.IdempotencyStore;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-P1 接入层门禁（桩执行器，无需活体 PIR）：认证/授权/幂等/双层配额/审计-with-appId。
 */
class GatewayAccessLayerTest {

    private static final long NOW = 1_785_600_000_000L;   // 固定服务器时钟，测试确定性
    private static final String APP = "risk-system";
    private static final String SECRET = "s3cr3t-key-material";

    private Database db;
    private AppRegistry apps;
    private AuditCenter audit;
    private QueryGateway gateway;
    private AtomicInteger executeCount;

    @BeforeEach
    void setup() {
        db = Database.inMemory("gw-" + UUID.randomUUID());
        byte[] masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        apps = new AppRegistry(db, new AesGcmEnvelope(masterKey));
        audit = new AuditCenter(db);
        var catalog = CapabilityCatalog.fromResource("/capabilities.yaml");
        var authenticator = new AppAuthenticator(apps, 60_000);
        var idempotency = new IdempotencyStore(db);
        var appQuota = new AppDailyQuota(db);

        executeCount = new AtomicInteger();
        SyncQueryExecutor stub = (cap, key) -> {
            executeCount.incrementAndGet();
            return key.equals("user-404")
                    ? new SyncQueryExecutor.QueryResult(false, null)
                    : new SyncQueryExecutor.QueryResult(true, "score-720");
        };
        gateway = new QueryGateway(db, authenticator, apps, catalog, idempotency, appQuota, audit, stub, "A-node", () -> NOW);

        apps.register(APP, "风控系统", SECRET);
        apps.grant(APP, "credit-check", 5);
    }

    private PpsRequest signed(String reqId, String key) {
        return signedWith(APP, SECRET, "credit-check", reqId, key);
    }

    private PpsRequest signedWith(String appId, String secret, String cap, String reqId, String key) {
        String nonce = UUID.randomUUID().toString();
        String canonical = HmacSigner.canonical(appId, NOW, nonce, cap, reqId, key);
        return new PpsRequest(appId, cap, reqId, NOW, nonce, key, HmacSigner.sign(secret, canonical));
    }

    @Test
    void 正常查询_命中_审计含appId不含查询键() {
        var r = gateway.handle(signed("order-1", "user-7"));
        assertTrue(r.hit());
        assertEquals("score-720", r.value());
        assertFalse(r.replayed());
        assertEquals(4.0, r.appQuotaRemaining(), 1e-9, "5 额度用 1 剩 4");

        assertTrue(audit.verifyChain());
        String flow = audit.byTask("order-1").stream()
                .map(e -> e.eventType() + e.payload()).reduce("", String::concat);
        assertTrue(flow.contains("PPS_QUERY_ACCEPTED") && flow.contains(APP),
                "审计须含 appId");
        assertFalse(flow.contains("user-7"), "审计绝不含查询键（匿踪目标）");
    }

    @Test
    void 幂等_同reqId重放不重复执行不重复扣配额() {
        var first = gateway.handle(signed("order-2", "user-7"));
        var replay = gateway.handle(signed("order-2", "user-7"));
        assertFalse(first.replayed());
        assertTrue(replay.replayed(), "同 reqId 二次调用应命中幂等");
        assertEquals(first.value(), replay.value());
        assertEquals(1, executeCount.get(), "执行只发生一次");
        assertEquals(4.0, replay.appQuotaRemaining(), 1e-9, "配额只扣一次");
    }

    @Test
    void 签名错误_认证失败() {
        var bad = new PpsRequest(APP, "credit-check", "order-3", NOW, "n", "user-7", "伪造签名");
        var e = assertThrows(GatewayException.class, () -> gateway.handle(bad));
        assertEquals(GatewayException.Reason.UNAUTHENTICATED, e.reason());
    }

    @Test
    void 时间戳过期_认证失败_防重放() {
        String nonce = "n";
        long stale = NOW - 120_000;   // 超出 60s 窗口
        String canonical = HmacSigner.canonical(APP, stale, nonce, "credit-check", "order-4", "user-7");
        var req = new PpsRequest(APP, "credit-check", "order-4", stale, nonce, "user-7",
                HmacSigner.sign(SECRET, canonical));
        assertEquals(GatewayException.Reason.UNAUTHENTICATED,
                assertThrows(GatewayException.class, () -> gateway.handle(req)).reason());
    }

    @Test
    void 未授权能力_被拒() {
        apps.register("other-app", "别的系统", "k");
        // other-app 未 grant credit-check
        var req = signedWith("other-app", "k", "credit-check", "order-5", "user-7");
        assertEquals(GatewayException.Reason.FORBIDDEN,
                assertThrows(GatewayException.class, () -> gateway.handle(req)).reason());
    }

    @Test
    void 应用配额耗尽_熔断且不执行() {
        for (int i = 0; i < 5; i++) {
            gateway.handle(signed("q-" + i, "user-" + i));
        }
        int before = executeCount.get();
        var e = assertThrows(GatewayException.class, () -> gateway.handle(signed("q-over", "user-x")));
        assertEquals(GatewayException.Reason.QUOTA_EXCEEDED, e.reason());
        assertEquals(before, executeCount.get(), "超配额不得执行");
    }

    @Test
    void 异步能力走同步端点_被拒() {
        var req = signedWith(APP, SECRET, "joint-consumption-stats", "order-6", "x");
        // 未 grant 且是 ASYNC——mode 校验应先于/独立于授权，这里断言 BAD_REQUEST
        apps.grant(APP, "joint-consumption-stats", 10);
        assertEquals(GatewayException.Reason.BAD_REQUEST,
                assertThrows(GatewayException.class, () -> gateway.handle(req)).reason());
    }
}
