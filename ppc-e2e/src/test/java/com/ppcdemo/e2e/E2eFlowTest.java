package com.ppcdemo.e2e;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.common.datagen.ValueGenerator;
import com.ppcdemo.dp.BudgetLedger;
import com.ppcdemo.dp.DpAggregator;
import com.ppcdemo.psi.LocalPsiRunner;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端链路（S 档缩小版）：PSI → 密态聚合（经字节边界）→ 解密 → DP 出库。
 */
class E2eFlowTest {

    @Test
    void 全链路密态聚合与明文一致且出库误差可控() throws Exception {
        long clampUpper = 100_000;
        var sets = IdSetGenerator.generate(42L, 10_000, 10_000, 0.5);
        long[] amounts = ValueGenerator.logNormalCents(43L, sets.partyB().length, clampUpper);
        Map<Long, Long> bData = new HashMap<>();
        for (int i = 0; i < sets.partyB().length; i++) {
            bData.put(sets.partyB()[i], amounts[i]);
        }

        BudgetLedger ledger = new BudgetLedger(10.0);
        PartyA partyA = new PartyA(2048, new DpAggregator(ledger));
        PartyB partyB = new PartyB(bData);
        Transport transport = Transport.inProcess();

        var psiResult = LocalPsiRunner.run("rr22", sets.partyB(), sets.partyA());
        Set<Long> intersection = psiResult.intersection();
        assertEquals(5_000, intersection.size());

        transport.send("pubkey", partyA.publicKeyBytes());
        partyB.receivePublicKey(transport.receive("pubkey"));
        transport.send("cipherSum", partyB.encryptedSumOf(intersection));
        long trueSum = partyA.decryptSum(transport.receive("cipherSum"));

        long expected = intersection.stream().mapToLong(bData::get).sum();
        assertEquals(expected, trueSum, "密态聚合必须与明文对照一致");

        double released = partyA.noisedForRelease("test", trueSum, 1.0, clampUpper);
        // ε=1、敏感度 1e5 的拉普拉斯噪声，对 ~2.5e8 分的总额相对误差应远小于 1%（P(>7e5)≈e^-7）
        assertTrue(Math.abs(released - trueSum) < trueSum * 0.01,
                "出库值偏差异常：" + released + " vs " + trueSum);
        assertEquals(9.0, ledger.remaining("test"), 1e-9, "预算应已扣减 ε=1");
    }
}
