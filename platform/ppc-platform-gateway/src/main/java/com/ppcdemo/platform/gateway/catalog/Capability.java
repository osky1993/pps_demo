package com.ppcdemo.platform.gateway.catalog;

/**
 * 能力（Capability，M5 §4）：业务系统唯一可见的调用单元。
 *
 * 技术参数（taskType/protocol/dataset/ε/输出粒度…）全部预置于此，业务系统不可见、不可覆盖——
 * 把隐私参数从业务开发者手里收回给懂的人。这是「优雅」的核心：协议演进对业务透明，
 * 能力目录即「已审批的数据使用场景清单」，天然对齐监管问询。
 */
public record Capability(
        String id,
        String displayName,
        Mode mode,
        String taskType,          // 底层任务类型（PIR_QUERY / PSI / JOINT_STATS…），业务不可见
        String protocol,          // 协议（pai_cks / rr22…），业务不可见
        String partner,           // 数据方 partyId
        String dataset,           // 数据集引用（数据方的库）
        String outputGranularity, // 输出粒度，业务不可见
        int valueBits,            // PIR value 比特长度
        long datasetDailyQuota,   // 数据集级配额
        long perAppDailyQuota) {  // 应用级配额

    public enum Mode { SYNC_QUERY, ASYNC_JOB, ONLINE_INFERENCE }

    public Capability {
        if (id == null || !id.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("能力 id 仅允许小写字母数字与连字符：" + id);
        }
        if (mode == null || taskType == null) {
            throw new IllegalArgumentException("能力必填字段缺失：" + id);
        }
    }
}
