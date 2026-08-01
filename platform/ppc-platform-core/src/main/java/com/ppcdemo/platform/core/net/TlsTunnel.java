package com.ppcdemo.platform.core.net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 协议面 mTLS 隧道（M2.5 backlog #1）：为不支持 TLS 的协议流（mpc4j netty）提供
 * 传输加密的边车。mpc4j 零改动——netty 明文只绑定 loopback，跨机流量走本隧道。
 *
 * 拓扑（每节点各一对）：
 *   入站：tlsServer  —— 公网 TLS 监听（强制客户端证书）→ 转发 loopback 明文 netty 端口
 *   出站：plainServer —— loopback 明文监听（本方 netty 作为“对方地址”连它）→ TLS 拨号对方公网端口
 *
 * 连接数为两方协议的常数级，采用线程对拷（每连接 2 泵线程），不引入 netty 依赖。
 */
public final class TlsTunnel implements AutoCloseable {

    private final ServerSocket listener;
    private final Thread acceptThread;
    private final List<Socket> live = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private TlsTunnel(ServerSocket listener, String targetDesc, SocketDialer dialer) {
        this.listener = listener;
        this.acceptThread = new Thread(() -> acceptLoop(dialer), "tls-tunnel-" + targetDesc);
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    @FunctionalInterface
    private interface SocketDialer {
        Socket dial() throws IOException;
    }

    /** 入站隧道：公网 TLS 监听（mTLS，needClientAuth）→ loopback 明文转发。 */
    public static TlsTunnel tlsServer(int tlsListenPort, SSLContext ssl,
                                      int targetLoopbackPort) throws IOException {
        SSLServerSocket server = (SSLServerSocket) ssl.getServerSocketFactory()
                .createServerSocket(tlsListenPort);
        server.setNeedClientAuth(true);
        return new TlsTunnel(server, "in:" + tlsListenPort,
                () -> new Socket(InetAddress.getLoopbackAddress(), targetLoopbackPort));
    }

    /** 出站隧道：loopback 明文监听 → TLS 拨号对方公网端口（对方未就绪时重试拨号）。 */
    public static TlsTunnel plainServer(int plainListenPort, SSLContext ssl,
                                        String peerHost, int peerTlsPort) throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), plainListenPort));
        return new TlsTunnel(server, "out:" + peerHost + ":" + peerTlsPort, () -> {
            IOException last = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    SSLSocket socket = (SSLSocket) ssl.getSocketFactory()
                            .createSocket(peerHost, peerTlsPort);
                    socket.startHandshake();
                    return socket;
                } catch (javax.net.ssl.SSLHandshakeException fatal) {
                    // 证书不受信等握手失败不可自愈，立即中止（不重试）
                    throw new IOException("TLS 握手失败（证书互信配置错误？）：" + fatal.getMessage(), fatal);
                } catch (IOException e) {
                    last = e;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("拨号被中断", ie);
                    }
                }
            }
            throw new IOException("对方 TLS 端点不可达：" + peerHost + ":" + peerTlsPort, last);
        });
    }

    public int listenPort() {
        return listener.getLocalPort();
    }

    private void acceptLoop(SocketDialer dialer) {
        while (!closed) {
            try {
                Socket inbound = listener.accept();
                live.add(inbound);
                daemonThread(() -> bridge(inbound, dialer), "tls-tunnel-bridge");
            } catch (IOException e) {
                if (!closed) {
                    // 监听异常但未关闭：记录后继续（单连接失败不拖垮隧道）
                    System.err.println("tls-tunnel accept 异常: " + e);
                }
            }
        }
    }

    private void bridge(Socket inbound, SocketDialer dialer) {
        Socket outbound = null;
        try {
            outbound = dialer.dial();
            live.add(outbound);
            Socket out = outbound;
            Thread pump = daemonThread(() -> pump(out, inbound), "tls-tunnel-pump");
            pump(inbound, outbound);
            pump.join();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            closeQuietly(inbound);
            closeQuietly(outbound);
        }
    }

    private static Thread daemonThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void pump(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException endOfStream) {
            // 任一方向断开即结束本连接（协议层自身有重连语义）
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无碍
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            listener.close();
        } catch (IOException ignored) {
            // 监听器关闭失败无碍
        }
        live.forEach(TlsTunnel::closeQuietly);
    }
}
