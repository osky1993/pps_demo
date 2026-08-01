package com.ppcdemo.platform.core;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.release.ReleaseGateway;
import com.ppcdemo.platform.core.store.TaskRepository;

import java.util.function.LongSupplier;

/**
 * 任务服务：状态机驱动 + 全程审计。
 * S1 范围：单方任务（LOCAL_STATS）闭环；双方协商任务（PSI/JOINT_STATS）S2/S3 扩展。
 */
public final class TaskService {

    private final TaskRepository tasks;
    private final AuditCenter audit;
    private final ReleaseGateway gateway;
    private final String selfParty;

    public TaskService(TaskRepository tasks, AuditCenter audit, ReleaseGateway gateway, String selfParty) {
        this.tasks = tasks;
        this.audit = audit;
        this.gateway = gateway;
        this.selfParty = selfParty;
    }

    public String create(TaskContract contract) {
        boolean singleParty = contract.responder() == null;
        tasks.insert(contract, TaskState.CREATED);
        audit.append(contract.taskId(), selfParty, "TASK_CREATED", contractSummary(contract));
        if (singleParty) {
            tasks.transit(contract.taskId(), TaskState.CREATED, TaskState.READY, null);
            audit.append(contract.taskId(), selfParty, "READY", "单方任务免协商");
        }
        return contract.taskId();
    }

    /**
     * 执行单方统计任务（S1 闭环）：READY→RUNNING→RELEASING→SUCCEEDED。
     * @param compute 真实统计值计算（数据接入 S4 接 connector，当前由调用方提供）
     * @return 出库值（已加噪）；真实值不出本方法
     */
    public double runLocalStats(String taskId, LongSupplier compute) {
        TaskContract contract = requireTask(taskId).contract();
        if (contract.taskType() != TaskContract.TaskType.LOCAL_STATS) {
            throw new IllegalArgumentException("非 LOCAL_STATS 任务：" + taskId);
        }
        tasks.transit(taskId, TaskState.READY, TaskState.RUNNING, null);
        audit.append(taskId, selfParty, "RUNNING", null);
        long trueValue;
        try {
            trueValue = compute.getAsLong();
        } catch (RuntimeException e) {
            tasks.transit(taskId, TaskState.RUNNING, TaskState.FAILED, e.getMessage());
            audit.append(taskId, selfParty, "FAILED", e.getMessage());
            throw e;
        }
        tasks.transit(taskId, TaskState.RUNNING, TaskState.RELEASING, null);
        try {
            double released = gateway.release(contract, trueValue);
            tasks.transit(taskId, TaskState.RELEASING, TaskState.SUCCEEDED, null);
            return released;
        } catch (RuntimeException e) {
            tasks.transit(taskId, TaskState.RELEASING, TaskState.REJECTED, e.getMessage());
            audit.append(taskId, selfParty, "RELEASE_REJECTED", e.getMessage());
            throw e;
        }
    }

    public TaskRepository.TaskRow requireTask(String taskId) {
        return tasks.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
    }

    private static String contractSummary(TaskContract c) {
        return "{\"type\": \"%s\", \"grain\": \"%s\", \"epsilon\": %s}"
                .formatted(c.taskType(), c.outputGranularity(), c.epsilon());
    }
}
