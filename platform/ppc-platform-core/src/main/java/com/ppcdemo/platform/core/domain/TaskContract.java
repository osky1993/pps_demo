package com.ppcdemo.platform.core.domain;

import java.util.UUID;

/**
 * 任务契约（M2 §4.1）：一经双方确认不可变更，重新协商 = 新任务。
 * 字段即 task 表列；contractFingerprint 为数据集内容指纹（S4 起强制复核）。
 */
public record TaskContract(
        String taskId,
        TaskType taskType,
        String initiator,
        String responder,          // 单方任务为 null
        String datasetLocal,
        String datasetPeer,        // 单方任务为 null
        String protocol,           // rr22 | kkrt16 | auto；非 PSI 类为 null
        OutputGranularity outputGranularity,
        Double epsilon,            // 出库不加噪的例外必须 epsilon=0 且走例外审批
        Long clampUpper,
        boolean batchLeakageAccepted,
        String contractFingerprint) {

    public enum TaskType { LOCAL_STATS, PSI, JOINT_STATS }

    public enum OutputGranularity { INTERSECTION_DETAIL, CARDINALITY_ONLY, NOISED_SCALAR }

    public static String newTaskId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public TaskContract {
        if (taskType == null || initiator == null || datasetLocal == null || outputGranularity == null) {
            throw new IllegalArgumentException("契约必填字段缺失");
        }
        if (outputGranularity == OutputGranularity.NOISED_SCALAR && (epsilon == null || clampUpper == null)) {
            throw new IllegalArgumentException("NOISED_SCALAR 出库必须声明 epsilon 与 clampUpper");
        }
        if (epsilon != null && epsilon < 0) {
            throw new IllegalArgumentException("epsilon 不能为负：" + epsilon);
        }
    }

    /** epsilon=0 表示不加噪出库——必须走例外审批（S4），网关默认拒绝。 */
    public boolean isNoiselessException() {
        return epsilon != null && epsilon == 0.0;
    }
}
