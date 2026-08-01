package com.ppcdemo.psi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S0 质量门禁：真实双 JVM 进程 + netty 网络栈（localhost）跑通 PSI 并通过真值校验。
 * client 进程校验失败会以退出码 2 结束，本测试据此判定。
 */
class NettyPsiTwoProcessTest {

    @Test
    void 双进程netty求交S档真值校验通过() throws Exception {
        int portServer = freePort();
        int portClient = freePort();
        Path dir = Files.createTempDirectory("netty-psi");
        Path serverYaml = writeYaml(dir, "server", portServer, portClient);
        Path clientYaml = writeYaml(dir, "client", portClient, portServer);

        Process server = launch(serverYaml, dir.resolve("server.log"));
        Process client = launch(clientYaml, dir.resolve("client.log"));

        assertTrue(client.waitFor(180, TimeUnit.SECONDS), "client 进程超时");
        assertTrue(server.waitFor(30, TimeUnit.SECONDS), "server 进程超时");
        String clientLog = Files.readString(dir.resolve("client.log"));
        assertEquals(0, client.exitValue(),
                "client 退出码非 0，日志：\n" + clientLog);
        assertTrue(clientLog.contains("校验通过"), "client 未输出校验通过：\n" + clientLog);
    }

    private static Path writeYaml(Path dir, String role, int localPort, int peerPort) throws IOException {
        Path yaml = dir.resolve(role + ".yaml");
        Files.writeString(yaml, """
                party:
                  role: %s
                  local: 127.0.0.1:%d
                  peer: 127.0.0.1:%d
                dataset:
                  profile: S
                  seed: 20260801
                  intersectionRatio: 0.50
                psi:
                  protocol: rr22
                """.formatted(role, localPort, peerPort));
        return yaml;
    }

    private static Process launch(Path yaml, Path log) throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "--enable-preview", "--add-modules", "jdk.incubator.vector", "-Xmx2g",
                "-cp", System.getProperty("java.class.path"),
                NettyPsiPartyMain.class.getName(), yaml.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        return pb.start();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
