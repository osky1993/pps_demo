package com.ppcdemo.platform.core.negotiation;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.store.TaskRepository;

import java.util.function.Function;

/**
 * 两方任务协商（M2 §2.1）：契约一经双方确认不可变更。
 * 发起侧：CREATED→NEGOTIATING→READY/REJECTED；
 * 响应侧：收到提议即落库 NEGOTIATING，审批后 READY/REJECTED——双方各存一份契约。
 */
public final class NegotiationService {

    private final TaskRepository tasks;
    private final AuditCenter audit;
    private final String selfParty;

    public NegotiationService(TaskRepository tasks, AuditCenter audit, String selfParty) {
        this.tasks = tasks;
        this.audit = audit;
        this.selfParty = selfParty;
    }

    /** 发起侧：创建并向对方提议。 */
    public TaskState initiate(TaskContract contract, PeerChannel channel) {
        tasks.insert(contract, TaskState.CREATED);
        audit.append(contract.taskId(), selfParty, "TASK_CREATED", null);
        tasks.transit(contract.taskId(), TaskState.CREATED, TaskState.NEGOTIATING, null);
        PeerChannel.Decision decision = channel.propose(contract);
        if (decision.approved()) {
            tasks.transit(contract.taskId(), TaskState.NEGOTIATING, TaskState.READY, null);
            audit.append(contract.taskId(), selfParty, "CONTRACT_CONFIRMED", "对方已审批");
            return TaskState.READY;
        }
        tasks.transit(contract.taskId(), TaskState.NEGOTIATING, TaskState.REJECTED, decision.reason());
        audit.append(contract.taskId(), selfParty, "PEER_REJECTED", decision.reason());
        return TaskState.REJECTED;
    }

    /** 响应侧：收到对方提议，交审批函数裁决（真实部署中挂人工审批，测试中挂策略）。 */
    public PeerChannel.Decision receiveProposal(TaskContract contract,
                                                Function<TaskContract, PeerChannel.Decision> approver) {
        tasks.insert(contract, TaskState.NEGOTIATING);
        audit.append(contract.taskId(), selfParty, "PROPOSAL_RECEIVED", null);
        PeerChannel.Decision decision = approver.apply(contract);
        if (decision.approved()) {
            tasks.transit(contract.taskId(), TaskState.NEGOTIATING, TaskState.READY, null);
            audit.append(contract.taskId(), selfParty, "APPROVED", null);
        } else {
            tasks.transit(contract.taskId(), TaskState.NEGOTIATING, TaskState.REJECTED, decision.reason());
            audit.append(contract.taskId(), selfParty, "REJECTED", decision.reason());
        }
        return decision;
    }
}
