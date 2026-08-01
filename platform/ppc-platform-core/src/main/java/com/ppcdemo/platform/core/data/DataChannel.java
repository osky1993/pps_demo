package com.ppcdemo.platform.core.data;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 任务级跨方数据通道（公钥、密文Σ 等控制面小载荷；PSI 协议流不走这里）。
 * take 语义：取走即删（数据不在通道滞留）。
 * 实现：进程内（测试/单机双节点）与 mTLS HTTP（MtlsPeerChannel data 端点）。
 */
public interface DataChannel {

    void put(String taskId, String key, byte[] payload);

    /** 阻塞取件（取走即删）；超时抛 IllegalStateException。 */
    byte[] take(String taskId, String key, long timeoutSeconds);

    static DataChannel inProcess() {
        return new DataChannel() {
            private final Map<String, BlockingQueue<byte[]>> slots = new ConcurrentHashMap<>();

            @Override
            public void put(String taskId, String key, byte[] payload) {
                slots.computeIfAbsent(taskId + "/" + key, k -> new LinkedBlockingQueue<>()).add(payload);
            }

            @Override
            public byte[] take(String taskId, String key, long timeoutSeconds) {
                try {
                    byte[] payload = slots
                            .computeIfAbsent(taskId + "/" + key, k -> new LinkedBlockingQueue<>())
                            .poll(timeoutSeconds, TimeUnit.SECONDS);
                    if (payload == null) {
                        throw new IllegalStateException("数据通道超时：" + taskId + "/" + key);
                    }
                    return payload;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("数据通道中断", e);
                }
            }
        };
    }
}
