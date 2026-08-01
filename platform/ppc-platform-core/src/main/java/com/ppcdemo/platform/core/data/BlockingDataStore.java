package com.ppcdemo.platform.core.data;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 节点本地数据暂存（M2.5 #2）：对方节点经 mTLS 推送的载荷落在这里，
 * 本方 take 阻塞取件、取走即删。也是进程内 DataChannel 的底层实现。
 */
public final class BlockingDataStore {

    private final Map<String, BlockingQueue<byte[]>> slots = new ConcurrentHashMap<>();

    public void put(String taskId, String key, byte[] payload) {
        slots.computeIfAbsent(slot(taskId, key), k -> new LinkedBlockingQueue<>()).add(payload);
    }

    public byte[] take(String taskId, String key, long timeoutSeconds) {
        try {
            byte[] payload = slots.computeIfAbsent(slot(taskId, key), k -> new LinkedBlockingQueue<>())
                    .poll(timeoutSeconds, TimeUnit.SECONDS);
            if (payload == null) {
                throw new IllegalStateException("数据通道超时：" + slot(taskId, key));
            }
            return payload;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("数据通道中断", e);
        }
    }

    private static String slot(String taskId, String key) {
        return taskId + "/" + key;
    }
}
