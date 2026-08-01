package com.ppcdemo.platform.fate;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.release.ExceptionApprovalService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 模型出库治理（M3 §4.4，M3 的治理增量核心）：
 * - SCORE_ONLY 粒度：导出接口**无条件拒绝**（模型只允许加载进本联盟 Serving）；
 * - MODEL_EXPORT 粒度：须全部参与方审批（复用 ExceptionApprovalService 语义，审批对象=出库物）；
 * - 导出物 = guest 侧模型参数 JSON（自 FATE tracking API），SHA-256 指纹入注册表与审计链。
 */
public final class ModelReleaseService {

    private final ModelRegistry registry;
    private final ExceptionApprovalService approvals;
    private final AuditCenter audit;
    private final FateFlowClient fate;
    private final String selfParty;

    public ModelReleaseService(ModelRegistry registry, ExceptionApprovalService approvals,
                               AuditCenter audit, FateFlowClient fate, String selfParty) {
        this.registry = registry;
        this.approvals = approvals;
        this.audit = audit;
        this.fate = fate;
        this.selfParty = selfParty;
    }

    /** 出库审批通过后调用（审批本身经 ExceptionApprovalService.approve 逐方登记）。 */
    public void confirmReleaseApproved(TaskContract contract) {
        if (!approvals.fullyApproved(contract)) {
            throw new IllegalStateException("模型出库未获全部参与方审批：" + contract.taskId());
        }
        registry.transit(contract.taskId(), ModelRegistry.ModelState.TRAINED,
                ModelRegistry.ModelState.RELEASE_APPROVED, null);
        audit.append(contract.taskId(), selfParty, "MODEL_RELEASE_APPROVED", null);
    }

    /**
     * 导出模型包。契约出库粒度是硬闸门：
     * NOISED_SCALAR/CARDINALITY_ONLY/INTERSECTION_DETAIL 契约本就不该有模型；
     * 联邦契约以 batchLeakageAccepted 之外新增语义前，粒度沿用 OutputGranularity：
     * INTERSECTION_DETAIL ≈ MODEL_EXPORT（允许导出）；其余 ≈ SCORE_ONLY（拒绝）。
     */
    public Path export(TaskContract contract, int guestPartyId, Path targetDir) {
        boolean exportAllowed =
                contract.outputGranularity() == TaskContract.OutputGranularity.INTERSECTION_DETAIL;
        if (!exportAllowed) {
            audit.append(contract.taskId(), selfParty, "MODEL_EXPORT_REFUSED",
                    "{\"grain\": \"%s\"}".formatted(contract.outputGranularity()));
            throw new IllegalStateException(
                    "契约出库粒度为 SCORE_ONLY（模型仅限联盟内 Serving），导出被拒：" + contract.taskId());
        }
        ModelRegistry.ModelRow model = registry.find(contract.taskId())
                .orElseThrow(() -> new IllegalStateException("模型未注册：" + contract.taskId()));
        if (model.state() != ModelRegistry.ModelState.RELEASE_APPROVED) {
            throw new IllegalStateException("模型未处于 RELEASE_APPROVED 状态：" + model.state());
        }
        try {
            String payload = fate.outputModelSummary(model.fateJobId(), guestPartyId, "hetero_lr_0");
            Files.createDirectories(targetDir);
            Path file = targetDir.resolve("model-" + contract.taskId() + ".json");
            Files.writeString(file, payload, StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
            registry.transit(contract.taskId(), ModelRegistry.ModelState.RELEASE_APPROVED,
                    ModelRegistry.ModelState.EXPORTED, sha256);
            audit.append(contract.taskId(), selfParty, "MODEL_EXPORTED",
                    "{\"sha256\": \"%s\", \"path\": \"%s\"}".formatted(sha256, file));
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("模型导出失败：" + contract.taskId(), e);
        }
    }
}
