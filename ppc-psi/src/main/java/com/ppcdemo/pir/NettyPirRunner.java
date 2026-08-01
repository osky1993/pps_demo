package com.ppcdemo.pir;

import com.ppcdemo.psi.NettyPsiRunner;
import edu.alibaba.mpc4j.common.rpc.MpcAbortException;
import edu.alibaba.mpc4j.common.rpc.Party;
import edu.alibaba.mpc4j.common.rpc.Rpc;
import edu.alibaba.mpc4j.common.rpc.impl.netty.NettyParty;
import edu.alibaba.mpc4j.common.rpc.impl.netty.robust.RobustNettyRpc;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirClient;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirConfig;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirFactory;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 双进程/双机匿踪查询运行器（RobustNettyRpc）。与 LocalPirRunner 协议语义一致，仅换传输层。
 * 复用 PSI 的端点约定与 TLS 隧道（隧道在传输层，与协议族无关）。
 *
 * 角色：server=数据方（partyId 0，持库），client=查询方（partyId 1，获得 value）。
 */
public final class NettyPirRunner {

    public record NettyPirResult(byte[][] values, long elapsedMillis, long sentBytes) {
    }

    private NettyPirRunner() {
    }

    /** 数据方：阻塞直至一批查询应答完毕。 */
    public static NettyPirResult runServer(String protocol, Map<String, byte[]> keyValueMap,
                                           int valueBitLength, int batchNum,
                                           NettyPsiRunner.Endpoint local, NettyPsiRunner.Endpoint peer)
            throws MpcAbortException {
        Rpc rpc = buildRpc(0, local, peer);
        rpc.connect();
        try {
            CpKsPirConfig config = PirSupport.configOf(protocol);
            CpKsPirServer<String> server = CpKsPirFactory.createServer(rpc, otherParty(rpc), config);
            long begin = System.nanoTime();
            server.init(keyValueMap, valueBitLength, batchNum);
            server.pir(batchNum);
            return new NettyPirResult(null, (System.nanoTime() - begin) / 1_000_000,
                    rpc.getSendByteLength());
        } finally {
            rpc.disconnect();
        }
    }

    /** 查询方：返回与 queries 顺序对齐的 value 数组（未命中位为 null 或占位）。 */
    public static NettyPirResult runClient(String protocol, int databaseSize, int valueBitLength,
                                           List<String> queries,
                                           NettyPsiRunner.Endpoint local, NettyPsiRunner.Endpoint peer)
            throws MpcAbortException {
        Rpc rpc = buildRpc(1, local, peer);
        rpc.connect();
        try {
            CpKsPirConfig config = PirSupport.configOf(protocol);
            CpKsPirClient<String> client = CpKsPirFactory.createClient(rpc, otherParty(rpc), config);
            long begin = System.nanoTime();
            client.init(databaseSize, valueBitLength, queries.size());
            byte[][] values = client.pir(new ArrayList<>(queries));
            return new NettyPirResult(values, (System.nanoTime() - begin) / 1_000_000,
                    rpc.getSendByteLength());
        } finally {
            rpc.disconnect();
        }
    }

    private static Rpc buildRpc(int ownPartyId, NettyPsiRunner.Endpoint local,
                                NettyPsiRunner.Endpoint peer) {
        NettyParty own = new NettyParty(ownPartyId, ownPartyId == 0 ? "server" : "client",
                local.host(), local.port());
        NettyParty other = new NettyParty(1 - ownPartyId, ownPartyId == 0 ? "client" : "server",
                peer.host(), peer.port());
        return new RobustNettyRpc(own, Set.of(own, other));
    }

    private static Party otherParty(Rpc rpc) {
        return rpc.getPartySet().stream()
                .filter(p -> p.getPartyId() != rpc.ownParty().getPartyId())
                .findFirst()
                .orElseThrow();
    }
}
