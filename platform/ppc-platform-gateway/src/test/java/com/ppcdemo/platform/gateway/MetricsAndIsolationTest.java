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
import com.ppcdemo.platform.gateway.obs.MetricsCollector;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-P3 门禁：可观测（指标采集 + Prometheus 导出）与多应用隔离（配额独立、跨应用不可见）。
 */
class MetricsAndIsolationTest {

    private Database db;
    private AppRegistry apps;
    private MetricsCollector metrics;
    private QueryGateway gateway;

    @BeforeEach
    void setup() {
        db = Database.inMemory("mi-" + UUID.randomUUID());
        byte[] mk = new byte[32];
        new SecureRandom().nextBytes(mk);
        apps = new AppRegistry(db, new AesGcmEnvelope(mk));
        AuditCenter audit = new AuditCenter(db);
        metrics = new MetricsCollector();
        SyncQueryExecutor stub = (cap, key) -> new SyncQueryExecutor.QueryResult(true, "v");
        gateway = new QueryGateway(db, new AppAuthenticator(apps, 60_000), apps,
                CapabilityCatalog.fromResource("/capabilities.yaml"), new IdempotencyStore(db),
                new AppDailyQuota(db), audit, stub, "A", System::currentTimeMillis, metrics);
    }

    private PpsRequest sign(String app, String secret, String reqId, String key) {
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String canonical = HmacSigner.canonical(app, ts, nonce, "credit-check", reqId, key);
        return new PpsRequest(app, "credit-check", reqId, ts, nonce, key,
                HmacSigner.sign(secret, canonical));
    }

    @Test
    void 指标采集与Prometheus导出() {
        apps.register("app1", "应用1", "k1");
        apps.grant("app1", "credit-check", 100);
        for (int i = 0; i < 3; i++) {
            gateway.handle(sign("app1", "k1", "r-" + i, "user-" + i));
        }
        assertEquals(3, metrics.count("accepted", "credit-check"));
        assertEquals(3, metrics.count("succeeded", "credit-check"));
        String prom = metrics.prometheus();
        assertTrue(prom.contains("pps_requests_total{metric=\"succeeded\",capability=\"credit-check\"} 3"));
        assertTrue(prom.contains("pps_latency_millis_count{capability=\"credit-check\"} 3"));
    }

    @Test
    void 多应用隔离_配额独立互不影响() {
        // capabilities.yaml 中 credit-check 应用级配额 = 5
        apps.register("app-a", "A", "ka");
        apps.register("app-b", "B", "kb");
        apps.grant("app-a", "credit-check", 5);
        apps.grant("app-b", "credit-check", 5);

        // app-a 用尽 5 次
        for (int i = 0; i < 5; i++) {
            gateway.handle(sign("app-a", "ka", "a-" + i, "u" + i));
        }
        assertEquals(GatewayException.Reason.QUOTA_EXCEEDED,
                assertThrows(GatewayException.class,
                        () -> gateway.handle(sign("app-a", "ka", "a-x", "ux"))).reason());

        // app-b 完全不受影响
        var rb = gateway.handle(sign("app-b", "kb", "b-0", "u0"));
        assertTrue(rb.hit());
        assertEquals(4.0, rb.appQuotaRemaining(), 1e-9, "app-b 配额独立");
    }

    @Test
    void 跨应用不可见_同reqId在不同应用是不同请求() {
        apps.register("app-a", "A", "ka");
        apps.register("app-b", "B", "kb");
        apps.grant("app-a", "credit-check", 5);
        apps.grant("app-b", "credit-check", 5);

        // 两应用用相同 reqId，互不为幂等命中（幂等键作用域是 app_id + req_id）
        var ra = gateway.handle(sign("app-a", "ka", "shared-id", "ua"));
        var rb = gateway.handle(sign("app-b", "kb", "shared-id", "ub"));
        assertTrue(ra.hit() && rb.hit());
        assertEquals(false, ra.replayed());
        assertEquals(false, rb.replayed(), "另一应用的同名 reqId 不构成重放");
    }
}
