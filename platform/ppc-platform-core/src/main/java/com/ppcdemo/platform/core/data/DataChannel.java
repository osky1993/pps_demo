package com.ppcdemo.platform.core.data;

/**
 * 任务级跨方数据通道（公钥、密文Σ 等控制面小载荷；PSI 协议流不走这里）。
 *
 * 分布式语义：put = 推送到**接收方**节点（跨网，mTLS）；take = 从**本节点**存储阻塞取件
 * （取走即删）。实现：进程内（测试/单机双节点）与 MtlsDataChannel（跨机）。
 */
public interface DataChannel {

    void put(String taskId, String key, byte[] payload);

    /** 阻塞取件（取走即删）；超时抛 IllegalStateException。 */
    byte[] take(String taskId, String key, long timeoutSeconds);

    /** 进程内实现：双方共享同一存储（单 JVM 双节点场景）。 */
    static DataChannel inProcess() {
        BlockingDataStore store = new BlockingDataStore();
        return new DataChannel() {
            @Override
            public void put(String taskId, String key, byte[] payload) {
                store.put(taskId, key, payload);
            }

            @Override
            public byte[] take(String taskId, String key, long timeoutSeconds) {
                return store.take(taskId, key, timeoutSeconds);
            }
        };
    }
}
