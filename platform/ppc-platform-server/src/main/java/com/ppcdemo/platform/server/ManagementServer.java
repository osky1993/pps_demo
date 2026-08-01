package com.ppcdemo.platform.server;

import com.ppcdemo.platform.core.connector.Dataset;
import com.ppcdemo.platform.core.connector.DatasetConnector;
import com.ppcdemo.platform.core.domain.TaskContract;
import com.ppcdemo.platform.core.domain.TaskState;
import com.ppcdemo.platform.core.psi.EngineRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 管理面 REST（M2.5 #3）：**仅绑定 loopback**——操作员在节点主机上经 CLI 访问，
 * 不对外网暴露（v1 安全姿态，远程管理需自行加跳板/隧道，手册注明）。
 * 请求/响应均为 properties 文本（与契约线格式一致，零 JSON 依赖）。
 *
 * 端点：
 *   POST /api/task/local-stats     dataset=..&epsilon=..&clampUpper=..[&budget=..]
 *   POST /api/task/joint-stats     role=initiator|responder&dataset=..&peerDataset=..（发起方先 create；
 *                                  执行需双方各自 POST /api/task/{id}/run 提供引擎端点参数）
 *   POST /api/task/{id}/run        role=server|client&localPort=..&peerHost=..&peerPort=..[&output=..]
 *   GET  /api/task/{id}            任务状态
 *   GET  /api/task/{id}/result     出库结果（唯一合法读取口）
 *   GET  /api/task/{id}/audit      审计流水
 *   GET  /api/budget?dataset=..    预算余额
 *   POST /api/task/{id}/approve-exception  party=..&approver=..
 */
public final class ManagementServer implements AutoCloseable {

    private final PlatformNode node;
    private final HttpServer server;

    public ManagementServer(PlatformNode node, int port) throws IOException {
        this.node = node;
        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", this::dispatch);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            Properties p = new Properties();
            if ("POST".equals(ex.getRequestMethod())) {
                p.load(new StringReader(new String(ex.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)));
            } else if (query != null) {
                p.load(new StringReader(query.replace('&', '\n')));
            }
            try {
                String body = route(path, ex.getRequestMethod(), p);
                respond(ex, 200, body);
            } catch (IllegalArgumentException | IllegalStateException e) {
                respond(ex, 400, "error=" + e.getMessage());
            } catch (RuntimeException e) {
                respond(ex, 500, "error=" + e);
            }
        }
    }

    private String route(String path, String method, Properties p) {
        if (path.equals("/api/task/local-stats") && method.equals("POST")) {
            return createAndRunLocalStats(p);
        }
        if (path.equals("/api/task/joint-stats") && method.equals("POST")) {
            return createJointStats(p);
        }
        if (path.equals("/api/budget") && method.equals("GET")) {
            return "remaining=" + node.budget.remaining(require(p, "dataset"));
        }
        String[] parts = path.split("/");
        // /api/task/{id}[/sub]
        if (parts.length >= 4 && parts[2].equals("task")) {
            String taskId = parts[3];
            String sub = parts.length > 4 ? parts[4] : "";
            return switch (sub) {
                case "" -> taskStatus(taskId);
                case "result" -> "released=" + node.gateway.releasedValue(taskId)
                        .map(String::valueOf).orElse("<无出库记录>");
                case "audit" -> node.audit.byTask(taskId).stream()
                        .map(e -> "%d|%s|%s|%s".formatted(e.seq(), e.party(), e.eventType(),
                                e.payload() == null ? "" : e.payload()))
                        .collect(Collectors.joining("\n"));
                case "run" -> runJointStats(taskId, p);
                case "approve-exception" -> approveException(taskId, p);
                default -> throw new IllegalArgumentException("未知子资源：" + sub);
            };
        }
        throw new IllegalArgumentException("未知端点：" + method + " " + path);
    }

    private String createAndRunLocalStats(Properties p) {
        String datasetRef = require(p, "dataset");
        Dataset dataset = DatasetConnector.resolve(datasetRef, node.connectors);
        node.budget.ensureAccount(datasetRef,
                Double.parseDouble(p.getProperty("budget", String.valueOf(node.config.budgetDefault()))));
        TaskContract contract = new TaskContract(TaskContract.newTaskId(),
                TaskContract.TaskType.LOCAL_STATS, node.config.party(), null, datasetRef, null, null,
                TaskContract.OutputGranularity.NOISED_SCALAR,
                Double.parseDouble(require(p, "epsilon")),
                Long.parseLong(require(p, "clampUpper")), false, dataset.fingerprint());
        var service = new com.ppcdemo.platform.core.TaskService(node.tasks, node.audit,
                node.gateway, node.config.party());
        String taskId = service.create(contract);
        double released = service.runLocalStats(taskId, () ->
                dataset.amountCentsById() == null
                        ? dataset.ids().length
                        : dataset.amountCentsById().values().stream().mapToLong(Long::longValue).sum());
        return "taskId=%s%nreleased=%s".formatted(taskId, released);
    }

    private String createJointStats(Properties p) {
        String datasetRef = require(p, "dataset");
        Dataset dataset = DatasetConnector.resolve(datasetRef, node.connectors);
        node.budget.ensureAccount(datasetRef,
                Double.parseDouble(p.getProperty("budget", String.valueOf(node.config.budgetDefault()))));
        TaskContract contract = new TaskContract(TaskContract.newTaskId(),
                TaskContract.TaskType.JOINT_STATS, node.config.party(),
                require(p, "responder"), datasetRef, require(p, "peerDataset"),
                p.getProperty("protocol", "rr22"),
                TaskContract.OutputGranularity.NOISED_SCALAR,
                Double.parseDouble(require(p, "epsilon")),
                Long.parseLong(require(p, "clampUpper")), false,
                p.getProperty("peerFingerprint"));
        TaskState state = node.negotiation.initiate(contract, node.peerChannel);
        return "taskId=%s%nstate=%s".formatted(contract.taskId(), state);
    }

    /** 执行联合统计（双方各自调用，引擎端点参数由运行手册约定）。同步阻塞至完成。 */
    private String runJointStats(String taskId, Properties p) {
        String role = require(p, "role");   // server=发起方 | client=数据方
        String datasetRef = node.tasks.find(taskId).orElseThrow().contract().datasetLocal();
        Dataset dataset = DatasetConnector.resolve(datasetRef, node.connectors);
        // 执行前指纹复核（S4 语义：防审批后换数据）
        dataset.requireFingerprint(node.tasks.find(taskId).orElseThrow()
                .contract().contractFingerprint());

        String localEp = "127.0.0.1:" + require(p, "localPort");
        String peerEp = require(p, "peerHost") + ":" + require(p, "peerPort");
        if ("server".equals(role)) {
            var job = new EngineRunner.EngineJob("server",
                    node.tasks.find(taskId).orElseThrow().contract().protocol(),
                    localEp, peerEp, null, null, 0, Integer.parseInt(require(p, "peerSize")));
            double released = node.joint.runInitiator(taskId, dataset.ids(), node.engine, job,
                    node.dataChannel, node.vault, node.gateway, 600);
            return "released=" + released;
        }
        Path output = Path.of(System.getProperty("java.io.tmpdir"), "psi-out-" + taskId + ".txt");
        var job = new EngineRunner.EngineJob("client",
                node.tasks.find(taskId).orElseThrow().contract().protocol(),
                localEp, peerEp, null, output, 0, Integer.parseInt(require(p, "peerSize")));
        CompletableFuture.runAsync(() -> node.joint.runResponder(taskId, dataset.ids(),
                dataset.amountCentsById(), node.engine, job, node.dataChannel, 600));
        return "state=RUNNING_ASYNC";
    }

    private String taskStatus(String taskId) {
        var row = node.tasks.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        return "taskId=%s%nstate=%s%ntype=%s%nfailReason=%s"
                .formatted(taskId, row.state(), row.contract().taskType(),
                        row.failReason() == null ? "" : row.failReason());
    }

    private String approveException(String taskId, Properties p) {
        node.exceptionApprovals.approve(taskId, require(p, "party"), require(p, "approver"));
        node.audit.append(taskId, node.config.party(), "EXCEPTION_APPROVED",
                "{\"party\": \"%s\", \"approver\": \"%s\"}"
                        .formatted(p.getProperty("party"), p.getProperty("approver")));
        return "approved=true";
    }

    private static String require(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
