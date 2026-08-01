package com.ppcdemo.platform.core.psi;

import java.nio.file.Path;
import java.util.Map;

/**
 * PSI 引擎调用抽象（M2 ADR-1：引擎在独立 JDK17 进程）。
 * 实现位于 ppc-engine-psi（进程一任务模式，SubprocessPsiEngineRunner）。
 * ADR 修订记录：一期采用「进程一任务」而非常驻 gRPC 服务——生命周期简单、故障隔离天然，
 * 常驻服务化留待任务并发量需要时再引入。
 */
public interface EngineRunner {

    record EngineJob(String role,            // server | client
                     String protocol,        // psi: rr22|kkrt16；pir: pai_cks|simple_naive
                     String localEndpoint,   // host:port（协议面）
                     String peerEndpoint,
                     Path idsFile,           // psi: 每行一个 long ID；pir: 库 CSV 或查询键
                     Path outputFile,        // client 侧产出；server 侧不写
                     int mySize,
                     int peerSize,
                     Map<String, String> extras) {   // 协议族扩展（M4：protocolFamily/valueBits/…）

        /** 兼容构造器：PSI 族调用点无需改动。 */
        public EngineJob(String role, String protocol, String localEndpoint, String peerEndpoint,
                         Path idsFile, Path outputFile, int mySize, int peerSize) {
            this(role, protocol, localEndpoint, peerEndpoint, idsFile, outputFile,
                    mySize, peerSize, Map.of());
        }

        public EngineJob {
            extras = extras == null ? Map.of() : Map.copyOf(extras);
        }
    }

    record EngineResult(int exitCode, long elapsedMillis, String log) {
        public boolean success() {
            return exitCode == 0;
        }
    }

    EngineResult run(EngineJob job);
}
