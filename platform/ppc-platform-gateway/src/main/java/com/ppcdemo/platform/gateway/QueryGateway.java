package com.ppcdemo.platform.gateway;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.gateway.api.GatewayException;
import com.ppcdemo.platform.gateway.api.PpsRequest;
import com.ppcdemo.platform.gateway.auth.AppAuthenticator;
import com.ppcdemo.platform.gateway.auth.AppRegistry;
import com.ppcdemo.platform.gateway.catalog.Capability;
import com.ppcdemo.platform.gateway.catalog.CapabilityCatalog;
import com.ppcdemo.platform.gateway.exec.SyncQueryExecutor;
import com.ppcdemo.platform.gateway.idem.IdempotencyStore;
import com.ppcdemo.platform.gateway.obs.MetricsCollector;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;

import java.time.LocalDate;
import java.util.function.LongSupplier;

/**
 * 同步查询网关（M5 §3.1 模式 A）：把接入层六件事串成一条链，且不绕治理内核。
 *
 * 认证(G2) → 授权 → 幂等认领 + 应用级配额预扣 + 审计-with-appId(G4/G5，同事务) → 执行 → 结果交付(G6)。
 *
 * 分层原则（守住「服务化不得成为治理后门」）：
 * - **应用级**配额是接入 concern，由本网关消费；
 * - **数据集级**配额是治理 concern，留在执行路径（PirTaskOrchestrator）里强制——
 *   任何入口（含直连编排）都受约束，不因绕过网关而失效。
 * 二者叠加即 §3.2「双层配额」，只是各自落在正确的层。
 */
public final class QueryGateway {

    public record Response(boolean hit, String value, boolean replayed,
                           double appQuotaRemaining) {
    }

    private final Database db;
    private final AppAuthenticator authenticator;
    private final AppRegistry apps;
    private final CapabilityCatalog catalog;
    private final IdempotencyStore idempotency;
    private final AppDailyQuota appQuota;
    private final AuditCenter audit;
    private final SyncQueryExecutor executor;
    private final String selfParty;
    private final LongSupplier clock;
    private final MetricsCollector metrics;

    public QueryGateway(Database db, AppAuthenticator authenticator, AppRegistry apps,
                        CapabilityCatalog catalog, IdempotencyStore idempotency,
                        AppDailyQuota appQuota, AuditCenter audit,
                        SyncQueryExecutor executor, String selfParty, LongSupplier clock) {
        this(db, authenticator, apps, catalog, idempotency, appQuota, audit, executor,
                selfParty, clock, MetricsCollector.NOOP);
    }

    public QueryGateway(Database db, AppAuthenticator authenticator, AppRegistry apps,
                        CapabilityCatalog catalog, IdempotencyStore idempotency,
                        AppDailyQuota appQuota, AuditCenter audit, SyncQueryExecutor executor,
                        String selfParty, LongSupplier clock, MetricsCollector metrics) {
        this.db = db;
        this.authenticator = authenticator;
        this.apps = apps;
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.appQuota = appQuota;
        this.audit = audit;
        this.executor = executor;
        this.selfParty = selfParty;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Response handle(PpsRequest req) {
        // ── G2 认证 ──
        try {
            authenticator.authenticate(req, clock.getAsLong());
        } catch (AppAuthenticator.AuthException e) {
            throw new GatewayException(GatewayException.Reason.UNAUTHENTICATED, "认证失败");
        }

        // ── 能力校验与授权 ──
        Capability cap = catalog.find(req.capability())
                .orElseThrow(() -> new GatewayException(
                        GatewayException.Reason.BAD_REQUEST, "未知能力：" + req.capability()));
        if (cap.mode() != Capability.Mode.SYNC_QUERY) {
            throw new GatewayException(GatewayException.Reason.BAD_REQUEST,
                    "能力 %s 非同步查询模式，应走异步作业提交".formatted(cap.id()));
        }
        if (!apps.hasGrant(req.appId(), cap.id())) {
            throw new GatewayException(GatewayException.Reason.FORBIDDEN,
                    "应用未获授权调用能力：" + cap.id());
        }
        if (req.body() == null || req.body().isBlank()) {
            throw new GatewayException(GatewayException.Reason.BAD_REQUEST, "查询键为空");
        }

        LocalDate day = LocalDate.ofEpochDay(clock.getAsLong() / 86_400_000L);
        Integer dailyLimit = apps.dailyLimit(req.appId(), cap.id());
        long perApp = dailyLimit != null ? dailyLimit : cap.perAppDailyQuota();
        appQuota.ensureToday(req.appId(), cap.id(), (int) perApp, day);

        // ── 幂等认领 + 应用级配额预扣 + 审计（同事务，G4/G5）──
        // 数据集级配额是治理 concern，在执行路径（orchestrator）里强制，不在此重复消费
        IdempotencyStore.Claim claim = db.inTransaction(conn -> {
            IdempotencyStore.Claim c = idempotency.claimInTx(conn, req.appId(), req.reqId(), cap.id());
            if (c.fresh()) {
                try {
                    appQuota.consumeInTx(conn, req.appId(), cap.id(), day);
                } catch (AppDailyQuota.QuotaExceededException e) {
                    // 认领与配额同事务：抛出即整体回滚，失败不扣、reqId 未被占用
                    throw new GatewayException(GatewayException.Reason.QUOTA_EXCEEDED, e.getMessage());
                }
                // 审计带 appId：可回答「哪个业务系统、凭哪个请求、调了哪个能力」——绝不含查询键
                audit.appendInTx(conn, req.reqId(), selfParty, "PPS_QUERY_ACCEPTED",
                        "{\"appId\": \"%s\", \"capability\": \"%s\"}"
                                .formatted(req.appId(), cap.id()));
            }
            return c;
        });

        // ── 重放：命中既有请求，不重复执行、不重复扣配额 ──
        if (!claim.fresh()) {
            return replayResponse(req, cap, day, claim.existing());
        }

        // ── 执行（在配额事务之外；失败则 over-charge，安全方向，M5 §3.3）──
        metrics.increment("accepted", cap.id());
        long begin = System.nanoTime();
        try {
            SyncQueryExecutor.QueryResult result = executor.execute(cap, req.body());
            idempotency.complete(req.appId(), req.reqId(), IdempotencyStore.ReqState.SUCCEEDED,
                    serialize(result));
            audit.append(req.reqId(), selfParty, "PPS_QUERY_SUCCEEDED",
                    "{\"appId\": \"%s\", \"hit\": %s}".formatted(req.appId(), result.hit()));
            metrics.increment("succeeded", cap.id());
            metrics.recordLatency(cap.id(), (System.nanoTime() - begin) / 1_000_000);
            return new Response(result.hit(), result.value(), false,
                    appQuota.remainingToday(req.appId(), cap.id(), day));
        } catch (RuntimeException e) {
            idempotency.complete(req.appId(), req.reqId(), IdempotencyStore.ReqState.FAILED, null);
            audit.append(req.reqId(), selfParty, "PPS_QUERY_FAILED",
                    "{\"appId\": \"%s\", \"error\": \"%s\"}"
                            .formatted(req.appId(), safe(e.getMessage())));
            metrics.increment("failed", cap.id());
            throw new GatewayException(GatewayException.Reason.INTERNAL, "查询执行失败");
        }
    }

    private Response replayResponse(PpsRequest req, Capability cap, LocalDate day,
                                    IdempotencyStore.Record existing) {
        double remaining = appQuota.remainingToday(req.appId(), cap.id(), day);
        return switch (existing.state()) {
            case SUCCEEDED -> {
                SyncQueryExecutor.QueryResult r = deserialize(existing.resultRef());
                yield new Response(r.hit(), r.value(), true, remaining);
            }
            case FAILED -> throw new GatewayException(GatewayException.Reason.INTERNAL,
                    "原请求已失败（幂等）：" + req.reqId());
            case ACCEPTED -> throw new GatewayException(GatewayException.Reason.BAD_REQUEST,
                    "原请求处理中（幂等），请勿重复提交：" + req.reqId());
        };
    }

    private static String serialize(SyncQueryExecutor.QueryResult r) {
        return r.hit() ? "HIT\t" + (r.value() == null ? "" : r.value()) : "MISS";
    }

    private static SyncQueryExecutor.QueryResult deserialize(String ref) {
        if (ref == null || ref.equals("MISS")) {
            return new SyncQueryExecutor.QueryResult(false, null);
        }
        String value = ref.startsWith("HIT\t") ? ref.substring(4) : ref;
        return new SyncQueryExecutor.QueryResult(true, value);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
