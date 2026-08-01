package com.ppcdemo.psi;

import edu.alibaba.mpc4j.common.rpc.MpcAbortException;
import edu.alibaba.mpc4j.common.rpc.Rpc;
import edu.alibaba.mpc4j.common.rpc.RpcManager;
import edu.alibaba.mpc4j.common.rpc.impl.memory.MemoryRpcManager;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiClient;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiConfig;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiFactory;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiServer;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单进程双线程 PSI 运行器（内存 RPC）：用于功能验证与 loopback 基准，
 * 排除网络因素的计算上限。双机场景用 NettyPsiRunner（协议语义一致）。
 */
public final class LocalPsiRunner {

    public record PsiResult(Set<Long> intersection,
                            long serverSentBytes,
                            long clientSentBytes,
                            long elapsedMillis) {
    }

    private LocalPsiRunner() {
    }

    /** @param protocol rr22 | kkrt16（poc.yaml 的 psi.protocol 语义） */
    public static PsiResult run(String protocol, long[] serverIds, long[] clientIds)
            throws InterruptedException, MpcAbortException {
        PsiConfig config = PsiSupport.configOf(protocol);
        RpcManager rpcManager = new MemoryRpcManager(2);
        Rpc serverRpc = rpcManager.getRpc(0);
        Rpc clientRpc = rpcManager.getRpc(1);
        serverRpc.connect();
        clientRpc.connect();
        try {
            PsiServer<ByteBuffer> server = PsiFactory.createServer(serverRpc, clientRpc.ownParty(), config);
            PsiClient<ByteBuffer> client = PsiFactory.createClient(clientRpc, serverRpc.ownParty(), config);

            Set<ByteBuffer> serverSet = PsiSupport.encode(serverIds);
            Set<ByteBuffer> clientSet = PsiSupport.encode(clientIds);

            long begin = System.nanoTime();
            AtomicReference<MpcAbortException> serverError = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try {
                    server.init(serverSet.size(), clientSet.size());
                    server.psi(serverSet, clientSet.size());
                } catch (MpcAbortException e) {
                    serverError.set(e);
                }
            }, "psi-server");
            serverThread.start();

            client.init(clientSet.size(), serverSet.size());
            Set<ByteBuffer> intersection = client.psi(clientSet, serverSet.size());
            serverThread.join();
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

            if (serverError.get() != null) {
                throw serverError.get();
            }
            return new PsiResult(PsiSupport.decode(intersection),
                    serverRpc.getSendByteLength(), clientRpc.getSendByteLength(), elapsedMillis);
        } finally {
            serverRpc.disconnect();
            clientRpc.disconnect();
        }
    }
}
