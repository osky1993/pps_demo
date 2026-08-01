package com.ppcdemo.engine.psi;

import com.ppcdemo.platform.core.net.TlsContexts;
import com.ppcdemo.platform.core.net.TlsTunnel;
import com.ppcdemo.psi.NettyPsiRunner;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.StringReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PSI 引擎进程入口（进程一任务）：job 文件（Properties）驱动，退出码即结果状态。
 * 0=成功；2=协议/参数错误；3=IO 错误。
 * client 侧把交集逐行写入 outputFile；server 侧不产出文件。
 *
 * 协议面 TLS（M2.5，默认关闭）：job 含 tlsEnabled=true 时，netty 明文只绑定 loopback，
 * 跨机流量经 TlsTunnel（mTLS）出入——见 ProtocolTlsProfile 与部署手册。
 */
public final class PsiEngineMain {

    private PsiEngineMain() {
    }

    public static void main(String[] args) {
        TlsTunnel inbound = null;
        TlsTunnel outbound = null;
        try {
            Properties job = new Properties();
            job.load(new StringReader(Files.readString(Path.of(args[0]))));

            String role = require(job, "role");
            String protocol = require(job, "protocol");
            var local = NettyPsiRunner.Endpoint.parse(require(job, "local"));
            var peer = NettyPsiRunner.Endpoint.parse(require(job, "peer"));
            long[] ids = readIds(Path.of(require(job, "idsFile")));
            int peerSize = Integer.parseInt(require(job, "peerSize"));

            if (Boolean.parseBoolean(job.getProperty("tlsEnabled", "false"))) {
                SSLContext ssl = TlsContexts.build(Path.of(require(job, "tlsKeyStore")),
                        require(job, "tlsStorePass").toCharArray(),
                        Path.of(require(job, "tlsTrustStore")));
                // netty 明文改绑 loopback；入站 TLS 隧道转发到它
                local = new NettyPsiRunner.Endpoint("127.0.0.1", local.port());
                inbound = TlsTunnel.tlsServer(Integer.parseInt(require(job, "tlsListenPort")),
                        ssl, local.port());
                // 出站：netty 把「对方」当成 loopback 明文端口，隧道 TLS 拨号对方公网端点
                var tlsPeer = NettyPsiRunner.Endpoint.parse(require(job, "tlsPeer"));
                int outPort = freeLoopbackPort();
                outbound = TlsTunnel.plainServer(outPort, ssl, tlsPeer.host(), tlsPeer.port());
                peer = new NettyPsiRunner.Endpoint("127.0.0.1", outPort);
                System.out.printf("engine[%s] 协议面 TLS 已启用：in=:%s out→%s%n",
                        role, job.getProperty("tlsListenPort"), job.getProperty("tlsPeer"));
            }

            if ("client".equals(role)) {
                // mpc4j RobustNettyRpc 的发送通道在对方未监听时首发失败后即死（重发被静默丢弃），
                // 因此 client 必须先确认对方端口可达再进入 connect() 握手（启动时序解耦）。
                awaitPeerReachable(peer, 120_000);
            }

            if ("server".equals(role)) {
                var result = NettyPsiRunner.runServer(protocol, ids, peerSize, local, peer);
                System.out.printf("engine[server] done: %d ms, sent %d B%n",
                        result.elapsedMillis(), result.sentBytes());
            } else {
                var result = NettyPsiRunner.runClient(protocol, ids, peerSize, local, peer);
                Path output = Path.of(require(job, "outputFile"));
                Set<String> lines = result.intersection().stream()
                        .map(String::valueOf).collect(Collectors.toSet());
                Files.writeString(output, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
                System.out.printf("engine[client] done: %d ms, |I|=%d, sent %d B%n",
                        result.elapsedMillis(), result.intersection().size(), result.sentBytes());
            }
            closeTunnels(inbound, outbound);
            System.exit(0);
        } catch (IOException e) {
            System.err.println("engine IO error: " + e);
            closeTunnels(inbound, outbound);
            System.exit(3);
        } catch (Exception e) {
            System.err.println("engine error: " + e);
            closeTunnels(inbound, outbound);
            System.exit(2);
        }
    }

    private static void closeTunnels(TlsTunnel inbound, TlsTunnel outbound) {
        if (inbound != null) {
            inbound.close();
        }
        if (outbound != null) {
            outbound.close();
        }
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitPeerReachable(NettyPsiRunner.Endpoint peer, long timeoutMillis)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            try (java.net.Socket probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress(peer.host(), peer.port()), 2_000);
                return;
            } catch (IOException notYet) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("对方协议端点不可达：" + peer.host() + ":" + peer.port(), notYet);
                }
                Thread.sleep(500);
            }
        }
    }

    private static String require(Properties job, String key) {
        String value = job.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("job 缺少字段：" + key);
        }
        return value;
    }

    private static long[] readIds(Path file) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.filter(l -> !l.isBlank()).mapToLong(Long::parseLong).toArray();
        }
    }
}
