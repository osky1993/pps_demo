package com.ppcdemo.e2e;

/**
 * 两方之间的字节传输边界。PoC 用进程内实现，但所有跨方数据强制走 byte[]——
 * 序列化可行性在此处被证明，换成 gRPC/netty 只是替换本接口实现（双机部署阶段的事）。
 * 任何不经过本接口的对象引用传递都视为"作弊"（破坏隐私边界论证）。
 */
public interface Transport {

    void send(String channel, byte[] payload);

    byte[] receive(String channel);

    /** 进程内实现：单槽位阻塞队列语义（PoC 两方交替收发，够用）。 */
    static Transport inProcess() {
        return new Transport() {
            private final java.util.Map<String, java.util.concurrent.BlockingQueue<byte[]>> channels =
                    new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public void send(String channel, byte[] payload) {
                queue(channel).add(payload);
            }

            @Override
            public byte[] receive(String channel) {
                try {
                    byte[] payload = queue(channel).poll(60, java.util.concurrent.TimeUnit.SECONDS);
                    if (payload == null) {
                        throw new IllegalStateException("通道超时：" + channel);
                    }
                    return payload;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("通道中断：" + channel, e);
                }
            }

            private java.util.concurrent.BlockingQueue<byte[]> queue(String channel) {
                return channels.computeIfAbsent(channel,
                        k -> new java.util.concurrent.LinkedBlockingQueue<>());
            }
        };
    }
}
