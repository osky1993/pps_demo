package com.ppcdemo.platform.gateway;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.gateway.api.GatewayException;
import com.ppcdemo.platform.gateway.api.PpsRequest;
import com.ppcdemo.platform.gateway.auth.AppAuthenticator;
import com.ppcdemo.platform.gateway.auth.AppRegistry;
import com.ppcdemo.platform.gateway.catalog.Capability;
import com.ppcdemo.platform.gateway.catalog.CapabilityCatalog;
import com.ppcdemo.platform.gateway.exec.AsyncJobExecutor;
import com.ppcdemo.platform.gateway.idem.IdempotencyStore;
import com.ppcdemo.platform.gateway.job.CallbackSender;
import com.ppcdemo.platform.gateway.job.JobRepository;
import com.ppcdemo.platform.gateway.quota.AppDailyQuota;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 异步作业网关（M5 §3.4，修 G3 同步阻塞）：受理即返回 jobId，后台工作池执行，回调优先轮询兜底。
 *
 * 受理事务（G4/G5）：幂等认领 + 应用配额预扣 + 回调地址 + 审计-with-appId，同事务提交后立即返回。
 * 执行在后台：每能力并发闸（Semaphore）防单能力打爆引擎；终态回调（签名+退避重试）。
 */
public final class AsyncJobGateway implements AutoCloseable {

    public record SubmitResponse(String jobId, String state) {
    }

    private final Database db;
    private final AppAuthenticator authenticator;
    private final AppRegistry apps;
    private final CapabilityCatalog catalog;
    private final IdempotencyStore idempotency;
    private final JobRepository jobs;
    private final AppDailyQuota appQuota;
    private final AuditCenter audit;
    private final AsyncJobExecutor executor;
    private final CallbackSender callbacks;
    private final String selfParty;
    private final LongSupplier clock;

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pps-job-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Semaphore> capacityGates = new ConcurrentHashMap<>();

    public AsyncJobGateway(Database db, AppAuthenticator authenticator, AppRegistry apps,
                           CapabilityCatalog catalog, IdempotencyStore idempotency, JobRepository jobs,
                           AppDailyQuota appQuota, AuditCenter audit, AsyncJobExecutor executor,
                           CallbackSender callbacks, String selfParty, LongSupplier clock) {
        this.db = db;
        this.authenticator = authenticator;
        this.apps = apps;
        this.catalog = catalog;
        this.idempotency = idempotency;
        this.jobs = jobs;
        this.appQuota = appQuota;
        this.audit = audit;
        this.executor = executor;
        this.callbacks = callbacks;
        this.selfParty = selfParty;
        this.clock = clock;
    }

    /** 受理并异步执行；@param callbackUrl 可空（空则仅轮询）。 */
    public SubmitResponse submit(PpsRequest req, String callbackUrl) {
        try {
            authenticator.authenticate(req, clock.getAsLong());
        } catch (AppAuthenticator.AuthException e) {
            throw new GatewayException(GatewayException.Reason.UNAUTHENTICATED, "认证失败");
        }
        Capability cap = catalog.find(req.capability())
                .orElseThrow(() -> new GatewayException(
                        GatewayException.Reason.BAD_REQUEST, "未知能力：" + req.capability()));
        if (cap.mode() != Capability.Mode.ASYNC_JOB) {
            throw new GatewayException(GatewayException.Reason.BAD_REQUEST,
                    "能力 %s 非异步作业模式".formatted(cap.id()));
        }
        if (!apps.hasGrant(req.appId(), cap.id())) {
            throw new GatewayException(GatewayException.Reason.FORBIDDEN,
                    "应用未获授权调用能力：" + cap.id());
        }

        LocalDate day = LocalDate.ofEpochDay(clock.getAsLong() / 86_400_000L);
        Integer limit = apps.dailyLimit(req.appId(), cap.id());
        appQuota.ensureToday(req.appId(), cap.id(),
                (int) (limit != null ? limit : cap.perAppDailyQuota()), day);

        IdempotencyStore.Claim claim = db.inTransaction(conn -> {
            IdempotencyStore.Claim c = idempotency.claimInTx(conn, req.appId(), req.reqId(), cap.id());
            if (c.fresh()) {
                try {
                    appQuota.consumeInTx(conn, req.appId(), cap.id(), day);
                } catch (AppDailyQuota.QuotaExceededException e) {
                    throw new GatewayException(GatewayException.Reason.QUOTA_EXCEEDED, e.getMessage());
                }
                if (callbackUrl != null && !callbackUrl.isBlank()) {
                    jobs.setCallbackInTx(conn, req.appId(), req.reqId(), callbackUrl);
                }
                audit.appendInTx(conn, req.reqId(), selfParty, "PPS_JOB_ACCEPTED",
                        "{\"appId\": \"%s\", \"capability\": \"%s\"}".formatted(req.appId(), cap.id()));
            }
            return c;
        });

        if (!claim.fresh()) {
            // 幂等：重复提交返回既有作业状态，绝不重复执行/扣费
            var existing = jobs.find(req.appId(), req.reqId()).orElseThrow();
            return new SubmitResponse(req.reqId(), existing.state().name());
        }

        // 后台执行（受理事务已提交，此处立即返回）
        workers.submit(() -> runJob(req, cap));
        return new SubmitResponse(req.reqId(), JobRepository.JobState.ACCEPTED.name());
    }

    private void runJob(PpsRequest req, Capability cap) {
        Semaphore gate = capacityGates.computeIfAbsent(cap.id(),
                k -> new Semaphore(Math.max(1, cap.maxConcurrency())));
        try {
            gate.acquire();   // 并发闸：单能力在跑作业数受限
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobs.fail(req.appId(), req.reqId(), "受理后被中断");
            return;
        }
        try {
            jobs.markRunning(req.appId(), req.reqId(), req.reqId());
            AsyncJobExecutor.JobResult result = executor.execute(cap, req.body());
            List<String> lines = result.resultLines() == null ? List.of() : result.resultLines();
            JobRepository.ResultKind kind = jobs.complete(req.appId(), req.reqId(),
                    result.summary(), lines);
            audit.append(req.reqId(), selfParty, "PPS_JOB_SUCCEEDED",
                    "{\"appId\": \"%s\", \"resultKind\": \"%s\"}".formatted(req.appId(), kind));
            fireCallback(req, "SUCCEEDED", result.summary());
        } catch (RuntimeException e) {
            jobs.fail(req.appId(), req.reqId(), e.getMessage());
            audit.append(req.reqId(), selfParty, "PPS_JOB_FAILED",
                    "{\"appId\": \"%s\"}".formatted(req.appId()));
            fireCallback(req, "FAILED", e.getMessage());
        } finally {
            gate.release();
        }
    }

    private void fireCallback(PpsRequest req, String state, String summary) {
        jobs.find(req.appId(), req.reqId()).ifPresent(job -> {
            if (job.callbackUrl() != null && !job.callbackUrl().isBlank()) {
                boolean ok = callbacks.send(req.appId(), job.callbackUrl(), req.reqId(),
                        state, summary == null ? "" : summary);
                audit.append(req.reqId(), selfParty, ok ? "PPS_CALLBACK_SENT" : "PPS_CALLBACK_FAILED",
                        "{\"appId\": \"%s\"}".formatted(req.appId()));
            }
        });
    }

    /** 轮询：作业状态（回调不可达的兜底，始终可用）。 */
    public JobRepository.JobRecord poll(String appId, String jobId) {
        return jobs.find(appId, jobId)
                .orElseThrow(() -> new GatewayException(
                        GatewayException.Reason.BAD_REQUEST, "作业不存在：" + jobId));
    }

    /** 结果拉取（G6 三档：INLINE 直取，PAGED 分页）。 */
    public JobRepository.ResultPage result(String appId, String jobId, int cursor, int limit) {
        JobRepository.JobRecord job = poll(appId, jobId);
        if (job.state() != JobRepository.JobState.SUCCEEDED) {
            throw new GatewayException(GatewayException.Reason.BAD_REQUEST,
                    "作业未成功，暂无结果：" + job.state());
        }
        if (job.resultKind() == JobRepository.ResultKind.INLINE) {
            String ref = job.resultRef() == null ? "" : job.resultRef();
            return new JobRepository.ResultPage(ref.isEmpty() ? List.of() : List.of(ref.split("\n")),
                    0, false);
        }
        return jobs.readResult(appId, jobId, cursor, limit);
    }

    /** 便捷：等待作业到终态（测试与同步化封装用；生产走回调/轮询）。 */
    public JobRepository.JobRecord awaitTerminal(String appId, String jobId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            JobRepository.JobRecord job = poll(appId, jobId);
            if (job.state() == JobRepository.JobState.SUCCEEDED
                    || job.state() == JobRepository.JobState.FAILED) {
                return job;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new GatewayException(GatewayException.Reason.INTERNAL, "等待作业终态超时：" + jobId);
    }

    @Override
    public void close() {
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
