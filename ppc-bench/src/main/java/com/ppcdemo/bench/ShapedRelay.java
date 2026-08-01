package com.ppcdemo.bench;

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
 * 软件网络整形中继（M2.5 #5）：为 loopback 上的协议流注入单向时延与带宽上限，
 * 模拟 WAN（等价 tc netem delay+rate 的用户态实现；macOS 无 netem 的替代方案）。
 *
 * 模型：每方向单泵线程，块投递时刻 = 首块到达 + delayMs + 累计字节×8/rateBps
 * ——同时刻画传播时延与序列化时延。真机矩阵仍以 netem 口径为准（报告注明）。
 */
public final class ShapedRelay implements AutoCloseable {

    private static final int CHUNK = 32 * 1024;

    private final ServerSocket listener;
    private final Thread acceptThread;
    private final List<Socket> live = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /**
     * @param listenPort 中继监听端口（对方把它当作目标端点）
     * @param targetPort 真实目标端口（loopback）；目标未就绪时拨号重试
     * @param delayMs    单向附加时延
     * @param rateBps    带宽上限（bit/s）；0 = 不限
     */
    public ShapedRelay(int listenPort, int targetPort, long delayMs, long rateBps) throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), listenPort));
        acceptThread = new Thread(() -> acceptLoop(targetPort, delayMs, rateBps),
                "shaped-relay-" + listenPort);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int port() {
        return listener.getLocalPort();
    }

    private void acceptLoop(int targetPort, long delayMs, long rateBps) {
        while (!closed) {
            try {
                Socket inbound = listener.accept();
                live.add(inbound);
                Thread bridge = new Thread(() -> bridge(inbound, targetPort, delayMs, rateBps));
                bridge.setDaemon(true);
                bridge.start();
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("shaped-relay accept 异常: " + e);
                }
            }
        }
    }

    private void bridge(Socket inbound, int targetPort, long delayMs, long rateBps) {
        Socket outbound = null;
        try {
            outbound = dialWithRetry(targetPort);
            live.add(outbound);
            Socket out = outbound;
            Thread reverse = new Thread(() -> pumpShaped(out, inbound, delayMs, rateBps));
            reverse.setDaemon(true);
            reverse.start();
            pumpShaped(inbound, outbound, delayMs, rateBps);
            reverse.join();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            closeQuietly(inbound);
            closeQuietly(outbound);
        }
    }

    private static Socket dialWithRetry(int targetPort) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                return new Socket(InetAddress.getLoopbackAddress(), targetPort);
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
        throw new IOException("目标端口不可达：" + targetPort, last);
    }

    private static void pumpShaped(Socket from, Socket to, long delayMs, long rateBps) {
        long streamStartNanos = -1;
        long cumulativeBytes = 0;
        byte[] buffer = new byte[CHUNK];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                long now = System.nanoTime();
                if (streamStartNanos < 0) {
                    streamStartNanos = now;
                }
                cumulativeBytes += read;
                long targetNanos = streamStartNanos + delayMs * 1_000_000
                        + (rateBps > 0 ? cumulativeBytes * 8_000_000_000L / rateBps : 0);
                long sleepNanos = targetNanos - now;
                if (sleepNanos > 0) {
                    Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException | InterruptedException endOfStream) {
            if (endOfStream instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
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
                // 收尾
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            listener.close();
        } catch (IOException ignored) {
            // 收尾
        }
        live.forEach(ShapedRelay::closeQuietly);
    }
}
