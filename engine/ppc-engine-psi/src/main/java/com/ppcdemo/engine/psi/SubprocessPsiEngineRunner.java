package com.ppcdemo.engine.psi;

import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.psi.ProtocolTlsProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 进程一任务的引擎启动器（platform 侧 EngineRunner 实现）。
 * javaBin/classpath 由节点配置注入（生产=JDK17 路径 + 引擎发布包；测试=当前 JVM 同款）。
 */
public final class SubprocessPsiEngineRunner implements EngineRunner {

    private final String javaBin;
    private final String classpath;
    private final long timeoutSeconds;
    private final ProtocolTlsProfile tlsProfile;   // null = 明文（默认）

    public SubprocessPsiEngineRunner(String javaBin, String classpath, long timeoutSeconds) {
        this(javaBin, classpath, timeoutSeconds, null);
    }

    /** 部署级 TLS 开关：传入 ProtocolTlsProfile 后，本 runner 启动的所有引擎走 mTLS 隧道。 */
    public SubprocessPsiEngineRunner(String javaBin, String classpath, long timeoutSeconds,
                                     ProtocolTlsProfile tlsProfile) {
        this.javaBin = javaBin;
        this.classpath = classpath;
        this.timeoutSeconds = timeoutSeconds;
        this.tlsProfile = tlsProfile;
    }

    /** 测试/单机部署便捷构造：复用当前 JVM 与 classpath（明文）。 */
    public static SubprocessPsiEngineRunner currentJvm(long timeoutSeconds) {
        return currentJvm(timeoutSeconds, null);
    }

    public static SubprocessPsiEngineRunner currentJvm(long timeoutSeconds, ProtocolTlsProfile tls) {
        return new SubprocessPsiEngineRunner(
                System.getProperty("java.home") + "/bin/java",
                System.getProperty("java.class.path"),
                timeoutSeconds, tls);
    }

    @Override
    public EngineResult run(EngineJob job) {
        try {
            Path jobFile = Files.createTempFile("psi-job", ".properties");
            String tlsSection = tlsProfile == null ? "" : """
                    tlsEnabled=true
                    tlsKeyStore=%s
                    tlsTrustStore=%s
                    tlsStorePass=%s
                    tlsListenPort=%d
                    tlsPeer=%s
                    """.formatted(tlsProfile.keyStore(), tlsProfile.trustStore(),
                    tlsProfile.storePass(), tlsProfile.tlsListenPort(), tlsProfile.tlsPeer());
            Files.writeString(jobFile, """
                    role=%s
                    protocol=%s
                    local=%s
                    peer=%s
                    idsFile=%s
                    outputFile=%s
                    peerSize=%d
                    """.formatted(job.role(), job.protocol(), job.localEndpoint(), job.peerEndpoint(),
                    job.idsFile(), job.outputFile() == null ? "" : job.outputFile(), job.peerSize())
                    + tlsSection);
            Path logFile = Files.createTempFile("psi-engine", ".log");

            long begin = System.nanoTime();
            Process process = new ProcessBuilder(List.of(javaBin,
                    "--enable-preview", "--add-modules", "jdk.incubator.vector", "-Xmx8g",
                    "-cp", classpath, PsiEngineMain.class.getName(), jobFile.toString()))
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new EngineResult(-1, timeoutSeconds * 1000, "引擎超时被终止");
            }
            long elapsed = (System.nanoTime() - begin) / 1_000_000;
            String log = Files.readString(logFile);
            Files.deleteIfExists(jobFile);
            Files.deleteIfExists(logFile);
            return new EngineResult(process.exitValue(), elapsed, log);
        } catch (IOException e) {
            throw new UncheckedIOException("引擎进程启动失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("引擎等待被中断", e);
        }
    }
}
