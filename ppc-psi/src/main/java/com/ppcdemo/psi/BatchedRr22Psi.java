package com.ppcdemo.psi;

import edu.alibaba.mpc4j.common.rpc.MpcAbortException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * RR22 分批求交（S0 遗留项 3，M2 约束 C2 的对策）：
 * client 集合按 batchSize 切分，逐批与 server 全集求交后取并集。
 *
 * 安全声明（契约必须显式接受，M2 §4.2 F9）：
 * 每批的交集规模对双方可见——比单次求交多泄露「交集在 client 集合中的分布」信息；
 * 审计记录批数与各批规模。server 集合每批全量参与，通信量约为单批的 ceil(clientSize/batchSize) 倍。
 */
public final class BatchedRr22Psi {

    public record BatchedResult(Set<Long> intersection, int batchCount, long elapsedMillis) {
    }

    private BatchedRr22Psi() {
    }

    public static BatchedResult run(long[] serverIds, long[] clientIds, int batchSize)
            throws InterruptedException, MpcAbortException {
        if (batchSize <= 0 || batchSize > PsiSupport.RR22_SINGLE_BATCH_MAX) {
            throw new IllegalArgumentException(
                    "batchSize 必须在 (0, %d]：%d".formatted(PsiSupport.RR22_SINGLE_BATCH_MAX, batchSize));
        }
        long begin = System.nanoTime();
        Set<Long> union = new HashSet<>();
        int batchCount = 0;
        for (int from = 0; from < clientIds.length; from += batchSize) {
            long[] batch = Arrays.copyOfRange(clientIds, from, Math.min(clientIds.length, from + batchSize));
            var result = LocalPsiRunner.run("rr22", serverIds, batch);
            union.addAll(result.intersection());
            batchCount++;
        }
        return new BatchedResult(union, batchCount, (System.nanoTime() - begin) / 1_000_000);
    }
}
