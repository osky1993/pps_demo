package com.ppcdemo.platform.core.negotiation;

import com.ppcdemo.platform.core.domain.TaskContract;

/**
 * 节点间控制面通道（M2 ADR-3 的控制面一侧）。
 * S2 提供两个实现：进程内 Loopback（测试/单机双节点）与 MtlsPeerChannel（HTTPS 双向认证）。
 * 协议面（PSI netty 流）不走本通道。
 */
public interface PeerChannel {

    record Decision(boolean approved, String reason) {
        public static Decision approve() {
            return new Decision(true, null);
        }

        public static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }

    /** 向对方节点提议任务契约，同步返回审批决定（S2 同步模型；异步审批留 M2.5）。 */
    Decision propose(TaskContract contract);
}
