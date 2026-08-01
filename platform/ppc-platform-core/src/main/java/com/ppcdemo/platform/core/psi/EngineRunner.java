package com.ppcdemo.platform.core.psi;

import java.nio.file.Path;

/**
 * PSI 引擎调用抽象（M2 ADR-1：引擎在独立 JDK17 进程）。
 * 实现位于 ppc-engine-psi（进程一任务模式，SubprocessPsiEngineRunner）。
 * ADR 修订记录：一期采用「进程一任务」而非常驻 gRPC 服务——生命周期简单、故障隔离天然，
 * 常驻服务化留待任务并发量需要时再引入。
 */
public interface EngineRunner {

    record EngineJob(String role,            // server | client
                     String protocol,        // rr22 | kkrt16
                     String localEndpoint,   // host:port（协议面）
                     String peerEndpoint,
                     Path idsFile,           // 每行一个 long ID
                     Path outputFile,        // client 侧写交集；server 侧不写
                     int mySize,
                     int peerSize) {
    }

    record EngineResult(int exitCode, long elapsedMillis, String log) {
        public boolean success() {
            return exitCode == 0;
        }
    }

    EngineResult run(EngineJob job);
}
