package com.ppcdemo.platform.core.jointstats;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.data.DataChannel;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.keyvault.KeyVault;
import com.ppcdemo.platform.core.phe.CipherAggregator;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.release.ReleaseGateway;
import com.ppcdemo.platform.core.store.TaskRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * 联合统计任务 DAG（M2 §4.1 / S3）：PSI → 数据方密态聚合 → 发起方解密 → DP 出库。
 *
 * 披露设计（较 M1 演示收紧）：数据方 B 任 PSI client 获得交集并即用即毁；
 * 发起方 A 任 PSI server，全程不接触交集明细，只得到密文Σ与加噪出库值。
 * A 的真实总额仅存在于 KeyVault 解密瞬间，出库唯一通道是 DP 网关。
 */
public final class JointStatsOrchestrator {

    private static final String KEY_PUBKEY = "pubkey";
    private static final String KEY_CIPHER_SUM = "cipherSum";
    private static final int KEY_BITS = 2048;

    private final TaskRepository tasks;
    private final AuditCenter audit;
    private final String selfParty;

    public JointStatsOrchestrator(TaskRepository tasks, AuditCenter audit, String selfParty) {
        this.tasks = tasks;
        this.audit = audit;
        this.selfParty = selfParty;
    }

    /** 发起方 A（PSI server）：发布公钥 → 陪跑 PSI → 收密文Σ → 保险箱解密 → DP 出库。 */
    public double runInitiator(String taskId, long[] ids, EngineRunner engine,
                               EngineRunner.EngineJob serverJob, DataChannel channel,
                               KeyVault vault, ReleaseGateway gateway, long takeTimeoutSeconds) {
        TaskContract contract = tasks.find(taskId).orElseThrow().contract();
        tasks.transit(taskId, TaskState.READY, TaskState.RUNNING, null);
        audit.append(taskId, selfParty, "JOINT_STARTED", "{\"role\": \"initiator/psi-server\"}");
        try {
            channel.put(taskId, KEY_PUBKEY, vault.createTaskKey(taskId, KEY_BITS));
            audit.append(taskId, selfParty, "PUBKEY_PUBLISHED", null);

            runEngine(taskId, engine, serverJob, ids);

            byte[] cipherSum = channel.take(taskId, KEY_CIPHER_SUM, takeTimeoutSeconds);
            long trueSum = vault.decrypt(taskId, cipherSum);
            vault.destroy(taskId);
            audit.append(taskId, selfParty, "CIPHER_SUM_DECRYPTED", "{\"keyDestroyed\": true}");

            tasks.transit(taskId, TaskState.RUNNING, TaskState.RELEASING, null);
            try {
                double released = gateway.release(contract, trueSum);
                tasks.transit(taskId, TaskState.RELEASING, TaskState.SUCCEEDED, null);
                return released;
            } catch (RuntimeException e) {
                tasks.transit(taskId, TaskState.RELEASING, TaskState.REJECTED, e.getMessage());
                audit.append(taskId, selfParty, "RELEASE_REJECTED", e.getMessage());
                throw e;
            }
        } catch (RuntimeException e) {
            failIfRunning(taskId, e);
            throw e;
        }
    }

    /** 数据方 B（PSI client）：跑 PSI 得交集（即用即毁）→ 密态聚合 → 发密文Σ。 */
    public void runResponder(String taskId, long[] ids, Map<Long, Long> amountCentsById,
                             EngineRunner engine, EngineRunner.EngineJob clientJob,
                             DataChannel channel, long takeTimeoutSeconds) {
        tasks.transit(taskId, TaskState.READY, TaskState.RUNNING, null);
        audit.append(taskId, selfParty, "JOINT_STARTED", "{\"role\": \"responder/psi-client\"}");
        try {
            runEngine(taskId, engine, clientJob, ids);

            long[] intersection = readAndDestroy(clientJob.outputFile());
            audit.append(taskId, selfParty, "INTERSECTION_USED_AND_DESTROYED",
                    "{\"cardinality\": %d}".formatted(intersection.length));

            long[] amounts = Arrays.stream(intersection)
                    .map(id -> {
                        Long amount = amountCentsById.get(id);
                        if (amount == null) {
                            throw new IllegalStateException("交集 ID 不在本方数据集：" + id);
                        }
                        return amount;
                    })
                    .toArray();
            byte[] pubkey = channel.take(taskId, KEY_PUBKEY, takeTimeoutSeconds);
            channel.put(taskId, KEY_CIPHER_SUM,
                    CipherAggregator.aggregateForTransfer(pubkey, amounts));
            audit.append(taskId, selfParty, "CIPHER_SUM_SENT",
                    "{\"aggregated\": %d, \"obfuscation\": \"final-sum-only\"}".formatted(amounts.length));

            tasks.transit(taskId, TaskState.RUNNING, TaskState.RELEASING, null);
            tasks.transit(taskId, TaskState.RELEASING, TaskState.SUCCEEDED, null);
        } catch (RuntimeException e) {
            failIfRunning(taskId, e);
            throw e;
        }
    }

    private void runEngine(String taskId, EngineRunner engine,
                           EngineRunner.EngineJob jobTemplate, long[] ids) {
        try {
            Path idsFile = Files.createTempFile("joint-ids-" + taskId, ".txt");
            StringBuilder sb = new StringBuilder(ids.length * 20);
            Arrays.stream(ids).forEach(id -> sb.append(id).append('\n'));
            Files.writeString(idsFile, sb);
            EngineRunner.EngineJob job = new EngineRunner.EngineJob(jobTemplate.role(),
                    jobTemplate.protocol(), jobTemplate.localEndpoint(), jobTemplate.peerEndpoint(),
                    idsFile, jobTemplate.outputFile(), ids.length, jobTemplate.peerSize());
            EngineRunner.EngineResult result = engine.run(job);
            Files.deleteIfExists(idsFile);
            if (!result.success()) {
                String logTail = result.log() == null ? "" : result.log()
                        .substring(Math.max(0, result.log().length() - 600));
                throw new IllegalStateException(
                        "PSI 引擎失败：exit=%d，日志尾部：%s".formatted(result.exitCode(), logTail));
            }
            audit.append(taskId, selfParty, "PSI_COMPLETED",
                    "{\"elapsedMillis\": %d}".formatted(result.elapsedMillis()));
        } catch (IOException e) {
            throw new UncheckedIOException("引擎数据文件操作失败", e);
        }
    }

    private long[] readAndDestroy(Path output) {
        try {
            long[] intersection;
            try (var lines = Files.lines(output)) {
                intersection = lines.filter(l -> !l.isBlank()).mapToLong(Long::parseLong).toArray();
            }
            Files.deleteIfExists(output);
            return intersection;
        } catch (IOException e) {
            throw new UncheckedIOException("交集文件读取失败", e);
        }
    }

    private void failIfRunning(String taskId, RuntimeException cause) {
        tasks.find(taskId).ifPresent(row -> {
            if (row.state() == TaskState.RUNNING) {
                tasks.transit(taskId, TaskState.RUNNING, TaskState.FAILED, cause.getMessage());
                audit.append(taskId, selfParty, "FAILED", cause.getMessage());
            }
        });
    }
}
