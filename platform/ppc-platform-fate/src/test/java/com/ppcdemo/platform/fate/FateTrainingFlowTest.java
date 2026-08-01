package com.ppcdemo.platform.fate;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.negotiation.NegotiationService;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3-S1 终验（CP1）：平台发起纵向 LR 训练端到端（活体 FATE standalone）。
 * 环境未起 FATE（localhost:9380 不可达）时自动跳过——CI 无 FATE 不红，
 * 本地/联调环境执行 deploy/fate/start-standalone.sh 后即真跑（约 3-4 分钟）。
 */
class FateTrainingFlowTest {

    private static final String FLOW_URL = System.getProperty("fate.flow.url", "http://localhost:9380");

    @Test
    void 平台发起纵向LR训练端到端() {
        FateFlowClient fate = new FateFlowClient(FLOW_URL);
        assumeTrue(fate.reachable(), "FATE Flow 不可达，跳过（起环境：deploy/fate/start-standalone.sh）");

        Database db = Database.inMemory("fed-" + UUID.randomUUID());
        TaskRepository tasks = new TaskRepository(db);
        AuditCenter audit = new AuditCenter(db);
        NegotiationService negotiation = new NegotiationService(tasks, audit, "A");

        // 1. 契约协商（响应方自动审批——开发态双方同容器）
        String taskId = "fed-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContract contract = new TaskContract(taskId, TaskContract.TaskType.JOINT_STATS,
                "A", "B", "fate://experiment/breast_hetero_guest",
                "fate://experiment/breast_hetero_host", "hetero_lr",
                TaskContract.OutputGranularity.NOISED_SCALAR, 1.0, 1L, false, null);
        assertEquals(TaskState.READY, negotiation.initiate(contract,
                c -> PeerChannel.Decision.approve()));

        // 2. 数据上传（S0 已上传过相同表，drop=1 幂等重传）
        fate.uploadServerLocal("/data/projects/fate/examples/data/breast_hetero_guest.csv",
                "experiment", "breast_hetero_guest", 1, 4);
        fate.uploadServerLocal("/data/projects/fate/examples/data/breast_hetero_host.csv",
                "experiment", "breast_hetero_host", 1, 4);

        // 3. 训练编排：提交→轮询→审计→模型注册→终态
        ModelRegistry registry = new ModelRegistry(db);
        FedTrainingOrchestrator orchestrator = new FedTrainingOrchestrator(
                tasks, audit, fate, registry, "A", 15 * 60_000);
        var outcome = orchestrator.run(taskId, TrainingSpec.heteroLrDefaults(
                "experiment", "breast_hetero_guest", "breast_hetero_host"));

        // 4. S1 治理证据
        assertEquals(TaskState.SUCCEEDED, tasks.find(taskId).orElseThrow().state());
        assertTrue(outcome.auc() > 0.8, "breast 样例 AUC 应显著高于随机：" + outcome.auc());
        assertTrue(outcome.alignedCount() > 0, "对齐基数应可审计：" + outcome.alignedCount());
        assertTrue(outcome.modelSummary().contains("Weight")
                        || outcome.modelSummary().contains("weight"),
                "模型概要应含权重信息");
        assertTrue(audit.verifyChain());
        for (String event : new String[]{"FED_TRAINING_STARTED", "FATE_JOB_SUBMITTED",
                "ALIGNED_CARDINALITY", "FATE_JOB_SUCCEEDED", "MODEL_TRAINED"}) {
            assertTrue(audit.byTask(taskId).stream().anyMatch(e -> e.eventType().equals(event)),
                    "审计缺少事件：" + event);
        }

        // 5. S2 治理证据：模型已注册 TRAINED；本契约粒度≈SCORE_ONLY → 导出无条件拒绝
        var model = registry.find(taskId).orElseThrow();
        assertEquals(ModelRegistry.ModelState.TRAINED, model.state());
        var release = new ModelReleaseService(registry,
                new com.ppcdemo.platform.core.release.ExceptionApprovalService(db),
                audit, fate, "A");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> release.export(contract, 9999,
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))),
                "SCORE_ONLY 粒度导出必须被拒");
        assertTrue(audit.byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("MODEL_EXPORT_REFUSED")));
        System.out.printf("联邦训练端到端通过：fateJob=%s AUC=%.4f 对齐=%d%n",
                outcome.fateJobId(), outcome.auc(), outcome.alignedCount());
    }
}
