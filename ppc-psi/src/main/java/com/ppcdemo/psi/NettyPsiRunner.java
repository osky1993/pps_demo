package com.ppcdemo.psi;

import edu.alibaba.mpc4j.common.rpc.MpcAbortException;
import edu.alibaba.mpc4j.common.rpc.Rpc;
import edu.alibaba.mpc4j.common.rpc.impl.netty.NettyParty;
import edu.alibaba.mpc4j.common.rpc.impl.netty.robust.RobustNettyRpc;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiClient;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiConfig;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiFactory;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiServer;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * 双进程/双机 PSI 运行器（mpc4j RobustNettyRpc，真实网络栈）。
 * 与 LocalPsiRunner 协议语义一致，仅替换 RPC 传输层——这正是 M1 设计的「薄封装」承诺。
 *
 * 角色约定：server=partyId 0，client=partyId 1；交集由 client 获得。
 * 注意：RobustNettyRpc 的底层 netty 通道为明文，跨公网部署必须叠加平台层 TLS 通道
 * 或网络层加密（S2 的 mTLS 控制面不覆盖协议面，见 S0 报告安全注记）。
 */
public final class NettyPsiRunner {

    public record Endpoint(String host, int port) {
        public static Endpoint parse(String hostPort) {
            int idx = hostPort.lastIndexOf(':');
            return new Endpoint(hostPort.substring(0, idx), Integer.parseInt(hostPort.substring(idx + 1)));
        }
    }

    public record NettyPsiResult(Set<Long> intersection,   // server 侧为 null
                                 long sentBytes,
                                 long elapsedMillis) {
    }

    private NettyPsiRunner() {
    }

    /** server 侧：阻塞直至协议完成。 */
    public static NettyPsiResult runServer(String protocol, long[] serverIds,
                                           int clientSize, Endpoint local, Endpoint peer)
            throws MpcAbortException {
        Rpc rpc = buildRpc(0, local, peer);
        rpc.connect();
        try {
            PsiConfig config = PsiSupport.configOf(protocol);
            PsiServer<ByteBuffer> server = PsiFactory.createServer(rpc, otherParty(rpc), config);
            Set<ByteBuffer> serverSet = PsiSupport.encode(serverIds);
            long begin = System.nanoTime();
            server.init(serverSet.size(), clientSize);
            server.psi(serverSet, clientSize);
            long elapsed = (System.nanoTime() - begin) / 1_000_000;
            return new NettyPsiResult(null, rpc.getSendByteLength(), elapsed);
        } finally {
            rpc.disconnect();
        }
    }

    /** client 侧：返回交集。 */
    public static NettyPsiResult runClient(String protocol, long[] clientIds,
                                           int serverSize, Endpoint local, Endpoint peer)
            throws MpcAbortException {
        Rpc rpc = buildRpc(1, local, peer);
        rpc.connect();
        try {
            PsiConfig config = PsiSupport.configOf(protocol);
            PsiClient<ByteBuffer> client = PsiFactory.createClient(rpc, otherParty(rpc), config);
            Set<ByteBuffer> clientSet = PsiSupport.encode(clientIds);
            long begin = System.nanoTime();
            client.init(clientSet.size(), serverSize);
            Set<ByteBuffer> intersection = client.psi(clientSet, serverSize);
            long elapsed = (System.nanoTime() - begin) / 1_000_000;
            return new NettyPsiResult(PsiSupport.decode(intersection), rpc.getSendByteLength(), elapsed);
        } finally {
            rpc.disconnect();
        }
    }

    private static Rpc buildRpc(int ownPartyId, Endpoint local, Endpoint peer) {
        // partyId 0 = server，1 = client；own 用 local 端点、对方用 peer 端点
        NettyParty own = new NettyParty(ownPartyId, ownPartyId == 0 ? "server" : "client",
                local.host(), local.port());
        NettyParty other = new NettyParty(1 - ownPartyId, ownPartyId == 0 ? "client" : "server",
                peer.host(), peer.port());
        return new RobustNettyRpc(own, Set.of(own, other));
    }

    private static edu.alibaba.mpc4j.common.rpc.Party otherParty(Rpc rpc) {
        return rpc.getPartySet().stream()
                .filter(p -> p.getPartyId() != rpc.ownParty().getPartyId())
                .findFirst()
                .orElseThrow();
    }
}
