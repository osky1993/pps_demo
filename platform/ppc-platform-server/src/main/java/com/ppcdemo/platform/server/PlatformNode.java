package com.ppcdemo.platform.server;

import com.ppcdemo.platform.core.audit.AuditCenter;
import com.ppcdemo.platform.core.budget.BudgetCenter;
import com.ppcdemo.platform.core.connector.CsvConnector;
import com.ppcdemo.platform.core.connector.DatasetConnector;
import com.ppcdemo.platform.core.connector.JdbcConnector;
import com.ppcdemo.platform.core.data.BlockingDataStore;
import com.ppcdemo.platform.core.jointstats.JointStatsOrchestrator;
import com.ppcdemo.platform.core.keyvault.EphemeralKeyVault;
import com.ppcdemo.platform.core.keyvault.KeyVault;
import com.ppcdemo.platform.core.negotiation.NegotiationService;
import com.ppcdemo.platform.core.negotiation.PeerChannel;
import com.ppcdemo.platform.core.net.MtlsDataChannel;
import com.ppcdemo.platform.core.net.MtlsNegotiationServer;
import com.ppcdemo.platform.core.net.MtlsPeerChannel;
import com.ppcdemo.platform.core.net.TlsContexts;
import com.ppcdemo.platform.core.psi.PsiTaskOrchestrator;
import com.ppcdemo.platform.core.psi.SubprocessPsiEngineRunner;
import com.ppcdemo.platform.core.release.ExceptionApprovalService;
import com.ppcdemo.platform.core.release.ReleaseGateway;
import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.core.store.TaskRepository;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.util.UUID;

/**
 * 节点装配（M2.5 #3）：领域层 + 控制面 mTLS 服务 + 跨方通道，一个 PlatformNode = 一个参与方节点。
 * 响应方审批策略（v1）：契约中本方数据集在 datasetAllowlist 内 → 通过，否则拒绝（决策留审计）。
 * 人工逐单审批为 backlog（需要异步协商语义）。
 */
public final class PlatformNode implements AutoCloseable {

    final NodeConfig config;
    final Database db;
    final TaskRepository tasks;
    final AuditCenter audit;
    final BudgetCenter budget;
    final ExceptionApprovalService exceptionApprovals;
    final ReleaseGateway gateway;
    final NegotiationService negotiation;
    final JointStatsOrchestrator joint;
    final PsiTaskOrchestrator psiOrchestrator;
    final KeyVault vault;
    final BlockingDataStore dataStore;
    final MtlsNegotiationServer controlServer;
    final MtlsPeerChannel peerChannel;
    final MtlsDataChannel dataChannel;
    final SubprocessPsiEngineRunner engine;
    final DatasetConnector[] connectors = {new CsvConnector(), new JdbcConnector()};

    public PlatformNode(NodeConfig config) throws IOException {
        this(config, new EphemeralKeyVault());
    }

    public PlatformNode(NodeConfig config, KeyVault vault) throws IOException {
        this.config = config;
        this.db = "mem".equals(config.dbPath())
                ? Database.inMemory("node-" + config.party() + "-" + UUID.randomUUID())
                : Database.file(config.dbPath());
        this.tasks = new TaskRepository(db);
        this.audit = new AuditCenter(db);
        this.budget = new BudgetCenter(db);
        this.exceptionApprovals = new ExceptionApprovalService(db);
        this.gateway = new ReleaseGateway(db, budget, audit, exceptionApprovals);
        this.negotiation = new NegotiationService(tasks, audit, config.party());
        this.joint = new JointStatsOrchestrator(tasks, audit, config.party());
        this.vault = vault;
        this.engine = SubprocessPsiEngineRunner.currentJvm(config.engineTimeoutSeconds());
        this.psiOrchestrator = new PsiTaskOrchestrator(db, tasks, audit, engine, config.party());

        SSLContext ssl = TlsContexts.build(config.keyStore(),
                config.storePass().toCharArray(), config.trustStore());
        this.dataStore = new BlockingDataStore();
        this.controlServer = new MtlsNegotiationServer(config.controlPort(), ssl,
                contract -> negotiation.receiveProposal(contract, this::approvalPolicy), dataStore);
        this.peerChannel = new MtlsPeerChannel(config.peerBaseUrl(), ssl);
        this.dataChannel = new MtlsDataChannel(config.peerBaseUrl(), ssl, dataStore);
    }

    private PeerChannel.Decision approvalPolicy(com.ppcdemo.platform.core.domain.TaskContract c) {
        if (config.datasetAllowlist().contains(c.datasetLocal())) {
            return PeerChannel.Decision.approve();
        }
        return PeerChannel.Decision.reject("数据集不在协作白名单：" + c.datasetLocal());
    }

    public int controlPort() {
        return controlServer.port();
    }

    @Override
    public void close() {
        controlServer.close();
    }
}
