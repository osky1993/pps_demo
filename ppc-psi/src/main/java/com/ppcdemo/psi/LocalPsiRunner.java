package com.ppcdemo.psi;

import edu.alibaba.mpc4j.common.rpc.MpcAbortException;
import edu.alibaba.mpc4j.common.rpc.Rpc;
import edu.alibaba.mpc4j.common.rpc.RpcManager;
import edu.alibaba.mpc4j.common.rpc.desc.SecurityModel;
import edu.alibaba.mpc4j.common.rpc.impl.memory.MemoryRpcManager;
import edu.alibaba.mpc4j.common.tool.EnvType;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiClient;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiConfig;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiFactory;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiServer;
import edu.alibaba.mpc4j.s2pc.pso.psi.cuckoo.kkrt16.Kkrt16PsiConfig;
import edu.alibaba.mpc4j.s2pc.pso.psi.mpoprf.rr22.Rr22PsiConfig;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 单进程双线程 PSI 运行器（内存 RPC）：用于功能验证与 loopback 基准，
 * 排除网络因素的计算上限（M1 设计文档 §3.5 loopback 场景）。
 * 双机 netty RPC 版本是 W3 的事，本类不承担。
 */
public final class LocalPsiRunner {

    /** ID 统一编码为 16 字节 block（与 mpc4j 测试用例的元素长度一致）。 */
    private static final int ELEMENT_BYTE_LENGTH = 16;

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
        PsiConfig config = configOf(protocol);
        RpcManager rpcManager = new MemoryRpcManager(2);
        Rpc serverRpc = rpcManager.getRpc(0);
        Rpc clientRpc = rpcManager.getRpc(1);
        serverRpc.connect();
        clientRpc.connect();
        try {
            PsiServer<ByteBuffer> server = PsiFactory.createServer(serverRpc, clientRpc.ownParty(), config);
            PsiClient<ByteBuffer> client = PsiFactory.createClient(clientRpc, serverRpc.ownParty(), config);

            Set<ByteBuffer> serverSet = encode(serverIds);
            Set<ByteBuffer> clientSet = encode(clientIds);

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
            return new PsiResult(decode(intersection),
                    serverRpc.getSendByteLength(), clientRpc.getSendByteLength(), elapsedMillis);
        } finally {
            serverRpc.disconnect();
            clientRpc.disconnect();
        }
    }

    private static PsiConfig configOf(String protocol) {
        PsiConfig config = switch (protocol) {
            case "rr22" -> new Rr22PsiConfig.Builder(SecurityModel.SEMI_HONEST).build();
            case "kkrt16" -> new Kkrt16PsiConfig.Builder().build();
            default -> throw new IllegalArgumentException("未知 PSI 协议：" + protocol + "（支持 rr22 | kkrt16）");
        };
        // 默认 EnvType.STANDARD 走 mpc4j-native-tool（C++，需另行 cmake 构建），
        // PoC 先用纯 Java 实现跑通（W1 fallback 策略）；native 加速对比是 W3 的基准项。
        config.setEnvType(EnvType.STANDARD_JDK);
        return config;
    }

    private static Set<ByteBuffer> encode(long[] ids) {
        return Arrays.stream(ids)
                .mapToObj(id -> ByteBuffer.wrap(
                        ByteBuffer.allocate(ELEMENT_BYTE_LENGTH).putLong(0, id).array()))
                .collect(Collectors.toSet());
    }

    private static Set<Long> decode(Set<ByteBuffer> elements) {
        return elements.stream()
                .map(buffer -> buffer.getLong(0))
                .collect(Collectors.toSet());
    }
}
