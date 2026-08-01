package com.ppcdemo.e2e;

import com.ppcdemo.common.config.PocConfig;
import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.common.datagen.ValueGenerator;
import com.ppcdemo.common.metrics.StopWatchRecorder;
import com.ppcdemo.dp.BudgetLedger;
import com.ppcdemo.dp.DpAggregator;
import com.ppcdemo.psi.LocalPsiRunner;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PoC-4 端到端演示（M1 设计文档 §6）：
 * A 银行 × B 电商联合统计「共同客户在 B 平台的年消费总额」，
 * A 不见 B 明细，B 不知 A 全量名单，出库结果强制过 DP 网关，全程审计。
 *
 * 链路：PSI 求交(mpc4j) → B 密态聚合仅盲化Σ(Paillier) → A 解密 → DP 加噪出库 → 审计 JSON。
 * 诚实边界声明（§6.3）：本演示中双方均知晓交集，是 PoC 设定而非平台终态；
 * 跨方数据均经 Transport 字节边界传递（序列化已证明，换 gRPC 只换传输实现）。
 */
public final class E2eDemoMain {

    private E2eDemoMain() {
    }

    public static void main(String[] args) throws Exception {
        Path yaml = Path.of(args.length > 0 ? args[0] : "poc.yaml");
        PocConfig config = PocConfig.load(yaml);
        int size = config.dataset().size();
        double epsilon = config.dp().epsilon();
        long clampUpper = config.dp().clampUpper();
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        StopWatchRecorder watch = new StopWatchRecorder();

        banner("任务 %s：联合统计「共同客户在 B 平台的年消费总额」（%s档 %d×%d, ε=%.1f）"
                .formatted(taskId, config.dataset().profile(), size, size, epsilon));

        // ── 数据准备（各方私有，演示程序知道真值仅用于终局校验） ──
        var sets = IdSetGenerator.generate(config.dataset().seed(), size, size,
                config.dataset().intersectionRatio());
        long[] amounts = ValueGenerator.logNormalCents(config.dataset().seed() + 1,
                sets.partyB().length, clampUpper);
        Map<Long, Long> bData = new HashMap<>(sets.partyB().length);
        for (int i = 0; i < sets.partyB().length; i++) {
            bData.put(sets.partyB()[i], amounts[i]);
        }

        BudgetLedger ledger = new BudgetLedger(config.dp().budgetTotal());
        PartyA partyA = new PartyA(config.phe().keyBits(), new DpAggregator(ledger));
        PartyB partyB = new PartyB(bData);
        Transport transport = Transport.inProcess();

        // ── 第 1 步：PSI 求交 ──
        step(1, "PSI 求交（%s）：A 见交集，B 见交集；双方均不见对方差集"
                .formatted(config.psi().protocol()));
        watch.start("psi");
        var psiResult = LocalPsiRunner.run(config.psi().protocol(), sets.partyB(), sets.partyA());
        Set<Long> intersection = psiResult.intersection();
        watch.stop("psi");
        System.out.printf("    交集 %d 条，%d ms，通信 %.1f MB%n",
                intersection.size(), watch.elapsedMillis("psi"),
                (psiResult.serverSentBytes() + psiResult.clientSentBytes()) / 1024.0 / 1024);

        // ── 第 2 步：公钥分发 ──
        step(2, "A 生成 Paillier 密钥对，公钥经传输边界发给 B（B 不可能解密任何密文）");
        watch.start("keyExchange");
        transport.send("pubkey", partyA.publicKeyBytes());
        partyB.receivePublicKey(transport.receive("pubkey"));
        watch.stop("keyExchange");

        // ── 第 3 步：B 密态聚合 ──
        step(3, "B 对交集用户消费额密态求和，仅盲化最终Σ后发出（单条明细密文从不出域）");
        watch.start("encryptedAggregate");
        transport.send("cipherSum", partyB.encryptedSumOf(intersection));
        watch.stop("encryptedAggregate");
        System.out.printf("    密态聚合 %d 条，%d ms%n", intersection.size(),
                watch.elapsedMillis("encryptedAggregate"));

        // ── 第 4 步：A 解密 ──
        step(4, "A 解密得到真实总额（仅此一个标量，B 方明细不可见）");
        watch.start("decrypt");
        long trueSum = partyA.decryptSum(transport.receive("cipherSum"));
        watch.stop("decrypt");
        System.out.printf("    真实总额 = %d 分（%.2f 万元）——此值仅 A 内部可见，禁止直接出库%n",
                trueSum, trueSum / 100.0 / 10000);

        // ── 第 5 步：DP 出库 ──
        step(5, "出库唯一通道：DP 网关扣减预算 ε=%.1f 后加噪".formatted(epsilon));
        watch.start("dpRelease");
        double released = partyA.noisedForRelease("联合消费统计", trueSum, epsilon, clampUpper);
        watch.stop("dpRelease");
        System.out.printf("    出库值 = %.0f 分（与真值偏差 %.4f%%），预算剩余 ε=%.1f%n",
                released, Math.abs(released - trueSum) * 100.0 / trueSum,
                ledger.remaining("联合消费统计"));

        // ── 终局校验（仅演示程序有权做：现实中无人同时持有双方明文） ──
        long expected = intersection.stream().mapToLong(bData::get).sum();
        if (trueSum != expected) {
            throw new IllegalStateException("密态聚合结果与明文对照不一致！");
        }
        System.out.println("\n  [演示程序校验] 密态聚合与明文对照一致 ✓");

        // ── 审计记录 ──
        Path auditPath = Path.of("e2e-audit-" + taskId + ".json");
        new AuditRecord(taskId, "联合统计-密态求和")
                .put("parties", "A(银行,出题方) | B(电商,数据方)")
                .put("datasetProfile", config.dataset().profile())
                .put("psiProtocol", config.psi().protocol())
                .put("intersectionSize", intersection.size())
                .put("pheKeyBits", config.phe().keyBits())
                .put("epsilonConsumed", epsilon)
                .put("clampUpper", clampUpper)
                .put("budgetRemaining", ledger.remaining("联合消费统计"))
                .put("releasedValue", Math.round(released))
                .put("elapsedMillisByPhase", watch.allMillis().toString())
                .writeTo(auditPath);
        System.out.printf("  审计记录 → %s%n", auditPath.toAbsolutePath());
    }

    private static void banner(String text) {
        System.out.println("═".repeat(72));
        System.out.println("  " + text);
        System.out.println("═".repeat(72));
    }

    private static void step(int n, String text) {
        System.out.printf("%n[%d] %s%n", n, text);
    }
}
