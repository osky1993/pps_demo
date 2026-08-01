package com.ppcdemo.platform.gateway.exec;

import com.ppcdemo.platform.gateway.catalog.Capability;

/**
 * 同步查询执行器（M5 §3.1 模式 A）：Gateway 把认证/授权/幂等/配额处理完后，
 * 委托本接口执行真实协议（PIR）。协议执行经既有编排（PirTaskOrchestrator），
 * **不绕治理内核**——接入层只做接入层的事。
 *
 * 实现：PirSyncQueryExecutor（活体，engine 模块）；测试用内存桩。
 */
public interface SyncQueryExecutor {

    record QueryResult(boolean hit, String value) {
    }

    /** @param queryKey 业务查询键——**不入审计、不落持久化**（匿踪目标，M5 §2.3） */
    QueryResult execute(Capability capability, String queryKey);
}
