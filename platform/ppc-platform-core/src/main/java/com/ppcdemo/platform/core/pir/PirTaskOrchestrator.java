package com.ppcdemo.platform.core.pir;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 匿踪查询任务编排（M4）。角色与 PSI 相反：**发起方 = 查询方（client）**，响应方 = 数据方（server）。
 *
 * 审计的主动克制（M4 设计 §4.3）：记录「谁、何时、查了几条、消耗多少配额、命中几条」，
 * **绝不记录查了哪些 key**——否则审计日志本身成为泄露面，与匿踪目标直接矛盾。
 */
public final class PirTaskOrchestrator {

    public record QueryOutcome(List<String> values, int hitCount, long elapsedMillis) {
    }

    private final Database db;
    private final TaskRepository tasks;
    private final AuditCenter audit;
    private final QueryQuotaCenter quotas;
    private final EngineRunner engine;
    private final String selfParty;

    public PirTaskOrchestrator(Database db, TaskRepository tasks, AuditCenter audit,
                               QueryQuotaCenter quotas, EngineRunner engine, String selfParty) {
        this.db = db;
        this.tasks = tasks;
        this.audit = audit;
        this.quotas = quotas;
        this.engine = engine;
        this.selfParty = selfParty;
    }

    /**
     * 查询方执行：预扣配额 → 引擎查询 → 读结果 → 审计（不含 key）。
     * @param queries    查询键（**不入审计、不落持久化**）
     * @param jobTemplate 引擎参数（协议/端点/库规模/value 长度）
     */
    public QueryOutcome runQuerier(String taskId, List<String> queries,
                                   EngineRunner.EngineJob jobTemplate) {
        TaskContract contract = tasks.find(taskId).orElseThrow().contract();
        tasks.transit(taskId, TaskState.READY, TaskState.RUNNING, null);
        // 配额预扣与审计同事务：扣了必留痕，失败则整体回滚
        db.inTransaction(conn -> {
            quotas.consumeInTx(conn, contract.datasetPeer(), queries.size());
            audit.appendInTx(conn, taskId, selfParty, "PIR_QUOTA_CONSUMED",
                    "{\"queryCount\": %d}".formatted(queries.size()));
            return null;
        });
        try {
            Path queryFile = Files.createTempFile("pir-q-" + taskId, ".txt");
            Files.writeString(queryFile, String.join("\n", queries) + "\n");
            EngineRunner.EngineJob job = new EngineRunner.EngineJob(jobTemplate.role(),
                    jobTemplate.protocol(), jobTemplate.localEndpoint(), jobTemplate.peerEndpoint(),
                    queryFile, jobTemplate.outputFile(), queries.size(), jobTemplate.peerSize(),
                    jobTemplate.extras());   // extras 必须透传：协议族分派依赖它

            long begin = System.nanoTime();
            EngineRunner.EngineResult result = engine.run(job);
            long elapsed = (System.nanoTime() - begin) / 1_000_000;
            Files.deleteIfExists(queryFile);   // 查询键即用即毁

            if (!result.success()) {
                tasks.transit(taskId, TaskState.RUNNING, TaskState.FAILED,
                        "引擎退出码 " + result.exitCode());
                audit.append(taskId, selfParty, "PIR_ENGINE_FAILED",
                        "{\"exitCode\": %d}".formatted(result.exitCode()));
                String logTail = result.log() == null ? "" : result.log()
                        .substring(Math.max(0, result.log().length() - 600));
                throw new IllegalStateException(
                        "PIR 引擎失败：exit=%d，日志尾部：%s".formatted(result.exitCode(), logTail));
            }

            List<String> values;
            try (var lines = Files.lines(job.outputFile())) {
                values = lines.toList();
            }
            Files.deleteIfExists(job.outputFile());
            int hits = (int) values.stream().filter(v -> !v.isBlank()).count();

            tasks.transit(taskId, TaskState.RUNNING, TaskState.RELEASING, null);
            audit.append(taskId, selfParty, "PIR_COMPLETED",
                    "{\"queryCount\": %d, \"hitCount\": %d, \"elapsedMillis\": %d}"
                            .formatted(queries.size(), hits, elapsed));
            tasks.transit(taskId, TaskState.RELEASING, TaskState.SUCCEEDED, null);
            return new QueryOutcome(values, hits, elapsed);
        } catch (IOException e) {
            throw new UncheckedIOException("PIR 任务文件操作失败", e);
        }
    }

    /** 数据方执行：提供库应答查询，全程不知查询内容。 */
    public void runResponder(String taskId, Path databaseFile, EngineRunner.EngineJob jobTemplate) {
        tasks.transit(taskId, TaskState.READY, TaskState.RUNNING, null);
        audit.append(taskId, selfParty, "PIR_SERVING_STARTED",
                "{\"protocol\": \"%s\"}".formatted(jobTemplate.protocol()));
        EngineRunner.EngineJob job = new EngineRunner.EngineJob(jobTemplate.role(),
                jobTemplate.protocol(), jobTemplate.localEndpoint(), jobTemplate.peerEndpoint(),
                databaseFile, null, jobTemplate.mySize(), jobTemplate.peerSize(),
                jobTemplate.extras());
        EngineRunner.EngineResult result = engine.run(job);
        if (!result.success()) {
            tasks.transit(taskId, TaskState.RUNNING, TaskState.FAILED,
                    "引擎退出码 " + result.exitCode());
            throw new IllegalStateException("PIR 数据方引擎失败：exit=" + result.exitCode());
        }
        tasks.transit(taskId, TaskState.RUNNING, TaskState.RELEASING, null);
        // 数据方视角：只知道应答了一批查询，不知内容也不知命中——审计如实反映这一无知
        audit.append(taskId, selfParty, "PIR_SERVED",
                "{\"batchNum\": %d, \"queryContentVisible\": false}".formatted(jobTemplate.peerSize()));
        tasks.transit(taskId, TaskState.RELEASING, TaskState.SUCCEEDED, null);
    }
}
