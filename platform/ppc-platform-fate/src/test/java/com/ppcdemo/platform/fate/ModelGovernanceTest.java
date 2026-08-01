package com.ppcdemo.platform.fate;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.release.ExceptionApprovalService;
import com.ppcdemo.platform.core.store.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3-S2 治理单测（无 FATE 依赖，模型取数用桩）：注册表状态机 + 出库审批 + 导出指纹。 */
class ModelGovernanceTest {

    @TempDir
    Path dir;

    private Database db;
    private ModelRegistry registry;
    private ExceptionApprovalService approvals;
    private AuditCenter audit;
    private ModelReleaseService release;
    private String taskId;

    /** 桩客户端：只覆写模型取数（治理路径不需要活体 FATE）。 */
    private static final class StubFate extends FateFlowClient {
        StubFate() {
            super("http://stub");
        }

        @Override
        public String outputModelSummary(String jobId, int guestPartyId, String component) {
            return "{\"encryptedWeight\": {\"w0\": \"...\"}, \"intercept\": 0.12}";
        }
    }

    @BeforeEach
    void setup() {
        db = Database.inMemory("model-gov-" + UUID.randomUUID());
        registry = new ModelRegistry(db);
        approvals = new ExceptionApprovalService(db);
        audit = new AuditCenter(db);
        release = new ModelReleaseService(registry, approvals, audit, new StubFate(), "A");
        taskId = "gov-" + UUID.randomUUID().toString().substring(0, 8);
        registry.register(taskId, "job-1", "model-id-1", "v1", "HETERO_LR", 0.98);
    }

    private TaskContract exportableContract() {
        return new TaskContract(taskId, TaskContract.TaskType.JOINT_STATS, "A", "B",
                "fate://g", "fate://h", "hetero_lr",
                TaskContract.OutputGranularity.INTERSECTION_DETAIL, null, null, false, null);
    }

    @Test
    void 全审批链路_导出含指纹() throws Exception {
        // 未全审批 → 确认被拒
        approvals.approve(taskId, "A", "建模负责人-甲");
        assertThrows(IllegalStateException.class, () -> release.confirmReleaseApproved(exportableContract()));

        // 全审批 → RELEASE_APPROVED → 导出 → EXPORTED + sha256
        approvals.approve(taskId, "B", "合规官-乙");
        release.confirmReleaseApproved(exportableContract());
        assertEquals(ModelRegistry.ModelState.RELEASE_APPROVED,
                registry.find(taskId).orElseThrow().state());

        Path exported = release.export(exportableContract(), 9999, dir);
        assertTrue(Files.exists(exported));
        var model = registry.find(taskId).orElseThrow();
        assertEquals(ModelRegistry.ModelState.EXPORTED, model.state());
        assertNotNull(model.modelSha256(), "导出必须留指纹");
        assertTrue(audit.verifyChain());
        assertTrue(audit.byTask(taskId).stream()
                .anyMatch(e -> e.eventType().equals("MODEL_EXPORTED")));
    }

    @Test
    void 未审批直接导出被拒() {
        assertThrows(IllegalStateException.class,
                () -> release.export(exportableContract(), 9999, dir),
                "TRAINED 状态（未审批）导出必须被拒");
    }

    @Test
    void 注册表非法状态迁移被拒() {
        assertThrows(IllegalStateException.class, () -> registry.transit(taskId,
                ModelRegistry.ModelState.TRAINED, ModelRegistry.ModelState.EXPORTED, null),
                "不得跳过审批直达 EXPORTED");
    }
}
