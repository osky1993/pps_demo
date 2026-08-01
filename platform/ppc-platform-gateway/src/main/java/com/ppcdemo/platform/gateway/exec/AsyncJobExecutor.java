package com.ppcdemo.platform.gateway.exec;

import com.ppcdemo.platform.gateway.catalog.Capability;

import java.util.List;

/**
 * 异步作业执行器（M5 §3.1 模式 B）：Gateway 受理后在后台工作线程调用，
 * 委托既有编排（PSI/联合统计）执行。**不绕治理内核**。
 */
public interface AsyncJobExecutor {

    /**
     * @param resultLines 结果行（PSI 交集明细、加噪统计值等）；决定结果交付档
     * @param summary     结果摘要（供回调内联携带，如"交集 3000 条"）
     */
    record JobResult(String summary, List<String> resultLines) {
    }

    JobResult execute(Capability capability, String params);
}
