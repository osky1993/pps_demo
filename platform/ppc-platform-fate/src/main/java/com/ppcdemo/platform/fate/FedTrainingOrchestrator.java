package com.ppcdemo.platform.fate;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.store.TaskRepository;

/**
 * 联邦训练任务编排（M3 §4.2）：READY→RUNNING（提交 FATE job + 轮询）→RELEASING（模型登记，
 * S2 起接出库审批）→SUCCEEDED。FATE 状态映射与关键里程碑全程落审计。
 */
public final class FedTrainingOrchestrator {

    public record TrainingOutcome(String fateJobId, double auc, long alignedCount,
                                  String modelSummary) {
    }

    private static final long POLL_INTERVAL_MILLIS = 10_000;

    private final TaskRepository tasks;
    private final AuditCenter audit;
    private final FateFlowClient fate;
    private final ModelRegistry registry;
    private final String selfParty;
    private final long timeoutMillis;

    public FedTrainingOrchestrator(TaskRepository tasks, AuditCenter audit, FateFlowClient fate,
                                   ModelRegistry registry, String selfParty, long timeoutMillis) {
        this.tasks = tasks;
        this.audit = audit;
        this.fate = fate;
        this.registry = registry;
        this.selfParty = selfParty;
        this.timeoutMillis = timeoutMillis;
    }

    /** 发起方（guest 侧）执行入口。数据上传由调用方先行完成（指纹复核后）。 */
    public TrainingOutcome run(String taskId, TrainingSpec spec) {
        tasks.transit(taskId, TaskState.READY, TaskState.RUNNING, null);
        audit.append(taskId, selfParty, "FED_TRAINING_STARTED",
                "{\"algorithm\": \"%s\", \"maxIter\": %d}".formatted(spec.algorithm(), spec.maxIter()));
        try {
            FateFlowClient.SubmitResult submit = fate.submitJob(
                    HeteroLrConfBuilder.conf(spec), HeteroLrConfBuilder.dsl());
            String jobId = submit.jobId();
            audit.append(taskId, selfParty, "FATE_JOB_SUBMITTED",
                    "{\"fateJobId\": \"%s\", \"modelId\": \"%s\"}".formatted(jobId, submit.modelId()));

            String status = pollUntilTerminal(taskId, jobId);
            if (!"success".equals(status)) {
                tasks.transit(taskId, TaskState.RUNNING, TaskState.FAILED, "FATE job " + status);
                audit.append(taskId, selfParty, "FATE_JOB_FAILED",
                        "{\"fateJobId\": \"%s\", \"status\": \"%s\"}".formatted(jobId, status));
                throw new IllegalStateException("FATE 任务未成功：" + jobId + " → " + status);
            }

            double auc = fate.evaluationAuc(jobId, spec.guestPartyId());
            long aligned = fate.intersectCount(jobId, spec.guestPartyId());
            String modelSummary = fate.outputModelSummary(jobId, spec.guestPartyId(), "hetero_lr_0");
            audit.append(taskId, selfParty, "ALIGNED_CARDINALITY",
                    "{\"intersectCount\": %d}".formatted(aligned));
            audit.append(taskId, selfParty, "FATE_JOB_SUCCEEDED",
                    "{\"fateJobId\": \"%s\", \"auc\": %s}".formatted(jobId, auc));

            tasks.transit(taskId, TaskState.RUNNING, TaskState.RELEASING, null);
            registry.register(taskId, jobId, submit.modelId(), submit.modelVersion(),
                    spec.algorithm().name(), auc);
            audit.append(taskId, selfParty, "MODEL_TRAINED",
                    "{\"fateJobId\": \"%s\", \"state\": \"TRAINED\"}".formatted(jobId));
            tasks.transit(taskId, TaskState.RELEASING, TaskState.SUCCEEDED, null);
            return new TrainingOutcome(jobId, auc, aligned, modelSummary);
        } catch (RuntimeException e) {
            tasks.find(taskId).ifPresent(row -> {
                if (row.state() == TaskState.RUNNING) {
                    tasks.transit(taskId, TaskState.RUNNING, TaskState.FAILED, e.getMessage());
                    audit.append(taskId, selfParty, "FAILED", e.getMessage());
                }
            });
            throw e;
        }
    }

    private String pollUntilTerminal(String taskId, String jobId) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String last = "unknown";
        while (System.currentTimeMillis() < deadline) {
            String status = fate.jobStatus(jobId);
            if (!status.equals(last)) {
                audit.append(taskId, selfParty, "FATE_JOB_STATUS", "{\"status\": \"" + status + "\"}");
                last = status;
            }
            switch (status) {
                case "success", "failed", "canceled" -> {
                    return status;
                }
                default -> sleep();
            }
        }
        fate.cancelJob(jobId);
        return "timeout-canceled";
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮询被中断", e);
        }
    }
}
