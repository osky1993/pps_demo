package com.ppcdemo.pir;

import edu.alibaba.mpc4j.common.rpc.MpcAbortException;
import edu.alibaba.mpc4j.common.rpc.Rpc;
import edu.alibaba.mpc4j.common.rpc.RpcManager;
import edu.alibaba.mpc4j.common.rpc.impl.memory.MemoryRpcManager;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirClient;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirConfig;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirFactory;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单进程双线程匿踪查询运行器（内存 RPC），用于功能验证与 loopback 基准。
 * 双机场景用 NettyPirRunner（协议语义一致）——与 PSI 的 Local/Netty 双运行器同构。
 *
 * 角色：server=数据方（持库），client=查询方（获得 value，且数据方无从得知查了哪个 key）。
 */
public final class LocalPirRunner {

    public record PirResult(byte[][] values,
                            long initMillis,
                            long queryMillis,
                            long serverSentBytes,
                            long clientSentBytes) {
    }

    private LocalPirRunner() {
    }

    /**
     * @param keyValueMap 数据方键值库（value 须等长，见 PirSupport.fitValue）
     * @param valueBitLength value 比特长度
     * @param queries     查询方的查询键（未命中键返回 null）
     */
    public static PirResult run(String protocol, Map<String, byte[]> keyValueMap,
                                int valueBitLength, List<String> queries)
            throws InterruptedException, MpcAbortException {
        CpKsPirConfig config = PirSupport.configOf(protocol);
        RpcManager rpcManager = new MemoryRpcManager(2);
        Rpc serverRpc = rpcManager.getRpc(0);
        Rpc clientRpc = rpcManager.getRpc(1);
        serverRpc.connect();
        clientRpc.connect();
        try {
            CpKsPirServer<String> server = CpKsPirFactory.createServer(
                    serverRpc, clientRpc.ownParty(), config);
            CpKsPirClient<String> client = CpKsPirFactory.createClient(
                    clientRpc, serverRpc.ownParty(), config);
            int n = keyValueMap.size();
            int batchNum = queries.size();

            // ── 初始化（客户端预处理 PIR 的成本集中在此，与库规模相关）──
            long initBegin = System.nanoTime();
            AtomicReference<Exception> serverError = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try {
                    server.init(keyValueMap, valueBitLength, batchNum);
                    server.pir(batchNum);
                } catch (MpcAbortException e) {
                    serverError.set(e);
                }
            }, "pir-server");
            serverThread.start();
            client.init(n, valueBitLength, batchNum);
            long initMillis = (System.nanoTime() - initBegin) / 1_000_000;

            // ── 在线查询（预处理换来的低通信量阶段）──
            long queryBegin = System.nanoTime();
            byte[][] values = client.pir(new ArrayList<>(queries));
            long queryMillis = (System.nanoTime() - queryBegin) / 1_000_000;

            serverThread.join();
            if (serverError.get() != null) {
                throw new IllegalStateException("PIR 数据方失败", serverError.get());
            }
            return new PirResult(values, initMillis, queryMillis,
                    serverRpc.getSendByteLength(), clientRpc.getSendByteLength());
        } finally {
            serverRpc.disconnect();
            clientRpc.disconnect();
        }
    }
}
