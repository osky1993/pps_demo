package com.ppcdemo.platform.gateway;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.keyvault.AesGcmEnvelope;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.gateway.auth.AppAuthenticator;
import com.ppcdemo.platform.gateway.auth.AppRegistry;
import com.ppcdemo.platform.gateway.catalog.CapabilityCatalog;
import com.ppcdemo.platform.gateway.exec.SyncQueryExecutor;
import com.ppcdemo.platform.gateway.http.PpsGatewayServer;
import com.ppcdemo.platform.gateway.idem.IdempotencyStore;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;
import com.ppcdemo.platform.gateway.sdk.PpsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-P1：SDK ↔ HTTP 服务端全往返——签名、传输、验签、幂等、错误码分类。
 * 绑 loopback 仅为测试；生产绑 0.0.0.0（G1，构造器已支持任意 bindHost）。
 */
class HttpRoundTripTest {

    private static final String APP = "risk-system";
    private static final String SECRET = "s3cr3t-key-material";

    private Database db;
    private PpsGatewayServer server;
    private PpsClient client;

    @BeforeEach
    void setup() throws IOException {
        db = Database.inMemory("http-" + UUID.randomUUID());
        byte[] masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        AppRegistry apps = new AppRegistry(db, new AesGcmEnvelope(masterKey));
        AuditCenter audit = new AuditCenter(db);
        var catalog = CapabilityCatalog.fromResource("/capabilities.yaml");

        SyncQueryExecutor stub = (cap, key) -> new SyncQueryExecutor.QueryResult(true, "score-688");
        var gateway = new QueryGateway(db, new AppAuthenticator(apps, 60_000), apps, catalog,
                new IdempotencyStore(db), new AppDailyQuota(db), audit, stub,
                "A-node", System::currentTimeMillis);

        apps.register(APP, "风控系统", SECRET);
        apps.grant(APP, "credit-check", 5);
        server = new PpsGatewayServer("127.0.0.1", 0, gateway);
        client = new PpsClient("http://127.0.0.1:" + server.port(), APP, SECRET);
    }

    @AfterEach
    void teardown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void SDK发起匿踪查询全往返() {
        var r = client.query("credit-check", "user-42", "biz-order-001");
        assertTrue(r.hit());
        assertEquals("score-688", r.value());
        assertFalse(r.replayed());
        assertEquals(4.0, r.appQuotaRemaining(), 1e-9);
    }

    @Test
    void SDK幂等重试同reqId返回原结果() {
        var first = client.query("credit-check", "user-42", "biz-order-002");
        var retry = client.query("credit-check", "user-42", "biz-order-002");
        assertFalse(first.replayed());
        assertTrue(retry.replayed());
        assertEquals(4.0, retry.appQuotaRemaining(), 1e-9, "重试不重复扣配额");
    }

    @Test
    void 错误密钥_401且不可重试() {
        var badClient = new PpsClient("http://127.0.0.1:" + server.port(), APP, "错误密钥");
        var e = assertThrows(PpsClient.PpsCallException.class,
                () -> badClient.query("credit-check", "user-1", "biz-order-003"));
        assertEquals(401, e.httpStatus());
        assertFalse(e.retryable(), "认证类错误不应重试");
    }
}
