package com.ppcdemo.engine.psi;

import com.ppcdemo.platform.core.psi.SubprocessPsiEngineRunner;

import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.common.datagen.ValueGenerator;
import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.connector.CsvConnector;
import com.ppcdemo.platform.core.connector.Dataset;
import com.ppcdemo.platform.core.data.DataChannel;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.jointstats.JointStatsOrchestrator;
import com.ppcdemo.platform.core.keyvault.EphemeralKeyVault;
import com.ppcdemo.platform.core.negotiation.NegotiationService;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.ppcdemo.platform.core.release.ReleaseGateway;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M2 验收演示（S5）：单机模拟双节点，全要素走真实组件——
 * CSV 数据接入（指纹）→ 协商审批（指纹入契约）→ 双 psi-engine 独立进程（netty）→
 * 数据方密态聚合（仅盲化Σ）→ 保险箱解密 → DP 网关出库（预算扣减）→ 双侧审计导出。
 *
 * 用法：java --enable-preview --add-modules jdk.incubator.vector ... AcceptanceDemoMain [S|M]
 * 双机部署时：两侧各起本程序的 server/client 半边（见 deploy/ 与部署手册）。
 */
public final class AcceptanceDemoMain {

    private record Node(String party, Database db, TaskRepository tasks, AuditCenter audit,
                        BudgetCenter budget, NegotiationService negotiation,
                        JointStatsOrchestrator joint, ReleaseGateway gateway, EphemeralKeyVault vault) {
        static Node of(String party) {
            Database db = Database.inMemory("acc-" + party + "-" + UUID.randomUUID());
            TaskRepository tasks = new TaskRepository(db);
            AuditCenter audit = new AuditCenter(db);
            BudgetCenter budget = new BudgetCenter(db);
            return new Node(party, db, tasks, audit, budget,
                    new NegotiationService(tasks, audit, party),
                    new JointStatsOrchestrator(tasks, audit, party),
                    new ReleaseGateway(db, budget, audit), new EphemeralKeyVault());
        }
    }

    public static void main(String[] args) throws Exception {
        String profile = args.length > 0 ? args[0] : "S";
        int size = "M".equals(profile) ? 1_000_000 : 10_000;
        long clampUpper = 100_000;
        long begin = System.nanoTime();

        banner("M2 验收演示：银行 A × 电商 B 联合统计（%s档 %d×%d, ε=1.0）".formatted(profile, size, size));

        // ── 0. 数据准备：落成 CSV，走真实 connector 接入 ──
        step("0", "数据接入：双方各自从 CSV 接入数据集并计算内容指纹");
        Path workDir = Files.createTempDirectory("m2-acceptance");
        var sets = IdSetGenerator.generate(20260801L, size, size, 0.3);
        long[] amounts = ValueGenerator.logNormalCents(1L, size, clampUpper);
        Path csvA = writeIdsCsv(workDir.resolve("bank-customers.csv"), sets.partyA());
        Path csvB = writeOrdersCsv(workDir.resolve("shop-orders.csv"), sets.partyB(), amounts);
        Dataset dsA = new CsvConnector().load("csv:" + csvA + "?idCol=0");
        Dataset dsB = new CsvConnector().load("csv:" + csvB + "?idCol=0&valueCol=1");
        System.out.printf("    A: %d 行, 指纹 %s%n    B: %d 行, 指纹 %s%n",
                dsA.ids().length, dsA.fingerprint(), dsB.ids().length, dsB.fingerprint());

        Node nodeA = Node.of("A");
        Node nodeB = Node.of("B");
        nodeA.budget().ensureAccount("ds://A/customers", 10.0);

        // ── 1. 协商：契约携带 B 方数据指纹，B 审批时复核 ──
        step("1", "契约协商：B 方审批（复核本方数据指纹一致才通过）");
        String taskId = "acc-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.JOINT_STATS,
                "A", "B", "ds://A/customers", "ds://B/orders", "rr22",
                TaskContract.OutputGranularity.NOISED_SCALAR, 1.0, clampUpper, false,
                dsB.fingerprint());
        PeerChannel channel = c -> nodeB.negotiation().receiveProposal(c, proposed -> {
            try {
                dsB.requireFingerprint(proposed.contractFingerprint());
                return PeerChannel.Decision.approve();
            } catch (Dataset.FingerprintMismatchException e) {
                return PeerChannel.Decision.reject(e.getMessage());
            }
        });
        TaskState after = nodeA.negotiation().initiate(contract, channel);
        System.out.println("    协商结果：" + after);

        // ── 2~5. 执行 DAG ──
        step("2-5", "PSI（双引擎进程/netty）→ B 密态聚合（仅盲化Σ）→ A 保险箱解密 → DP 出库");
        int portA = freePort();
        int portB = freePort();
        Path outputFile = workDir.resolve("intersection-" + taskId + ".txt");
        var serverJob = new EngineRunner.EngineJob("server", "rr22",
                "127.0.0.1:" + portA, "127.0.0.1:" + portB, null, null, 0, size);
        var clientJob = new EngineRunner.EngineJob("client", "rr22",
                "127.0.0.1:" + portB, "127.0.0.1:" + portA, null, outputFile, 0, size);
        DataChannel dataChannel = DataChannel.inProcess();
        EngineRunner engine = SubprocessPsiEngineRunner.currentJvm(600);

        CompletableFuture<Void> bSide = CompletableFuture.runAsync(() -> nodeB.joint()
                .runResponder(taskId, dsB.ids(), dsB.amountCentsById(), engine, clientJob,
                        dataChannel, 300));
        double released = nodeA.joint().runInitiator(taskId, dsA.ids(), engine, serverJob,
                dataChannel, nodeA.vault(), nodeA.gateway(), 300);
        bSide.join();
        long totalMillis = (System.nanoTime() - begin) / 1_000_000;

        // ── 6. 结果与治理证据 ──
        step("6", "结果与治理证据");
        System.out.printf("    出库值（加噪）：%.0f 分 ≈ %.2f 万元%n", released, released / 100 / 10_000);
        System.out.printf("    A 方预算剩余：ε=%.1f%n", nodeA.budget().remaining("ds://A/customers"));
        System.out.printf("    双侧任务状态：A=%s, B=%s%n",
                nodeA.tasks().find(taskId).orElseThrow().state(),
                nodeB.tasks().find(taskId).orElseThrow().state());
        System.out.printf("    审计链校验：A=%s, B=%s%n",
                nodeA.audit().verifyChain() ? "通过" : "断链！",
                nodeB.audit().verifyChain() ? "通过" : "断链！");

        System.out.println("\n  ── A 方审计流水 ──");
        nodeA.audit().byTask(taskId).forEach(e ->
                System.out.printf("    [%d] %-24s %s%n", e.seq(), e.eventType(),
                        e.payload() == null ? "" : e.payload()));
        System.out.println("  ── B 方审计流水 ──");
        nodeB.audit().byTask(taskId).forEach(e ->
                System.out.printf("    [%d] %-24s %s%n", e.seq(), e.eventType(),
                        e.payload() == null ? "" : e.payload()));

        System.out.printf("%n  全流程耗时 %d ms（SLA：联合统计 ≤ 90s）→ %s%n",
                totalMillis, totalMillis <= 90_000 ? "达标" : "超标");
        banner("验收演示完成");
    }

    private static Path writeIdsCsv(Path file, long[] ids) throws Exception {
        StringBuilder sb = new StringBuilder("customer_id\n");
        for (long id : ids) {
            sb.append(id).append('\n');
        }
        Files.writeString(file, sb);
        return file;
    }

    private static Path writeOrdersCsv(Path file, long[] ids, long[] amountCents) throws Exception {
        StringBuilder sb = new StringBuilder("user_id,amount_yuan\n");
        for (int i = 0; i < ids.length; i++) {
            sb.append(ids[i]).append(',')
                    .append(new BigDecimal(amountCents[i]).movePointLeft(2)).append('\n');
        }
        Files.writeString(file, sb);
        return file;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void banner(String text) {
        System.out.println("═".repeat(76));
        System.out.println("  " + text);
        System.out.println("═".repeat(76));
    }

    private static void step(String n, String text) {
        System.out.printf("%n[%s] %s%n", n, text);
    }
}
