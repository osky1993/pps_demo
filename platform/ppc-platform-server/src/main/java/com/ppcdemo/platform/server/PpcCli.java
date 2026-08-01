package com.ppcdemo.platform.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 节点管理 CLI（M2.5 #3）：管理面 REST 的薄客户端。
 *
 * 用法：PpcCli <mgmtBaseUrl> <命令> [k=v ...]
 * 命令：
 *   local-stats   dataset=csv:/path?... epsilon=1.0 clampUpper=100000
 *   joint-create  responder=B dataset=.. peerDataset=.. epsilon=1.0 clampUpper=100000
 *   joint-run     task=<id> role=server|client localPort=.. peerHost=.. peerPort=.. peerSize=..
 *   status        task=<id>
 *   result        task=<id>
 *   audit         task=<id>
 *   budget        dataset=..
 *   approve-exc   task=<id> party=.. approver=..
 */
public final class PpcCli {

    private PpcCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：PpcCli <mgmtBaseUrl> <命令> [k=v ...]（命令见类文档）");
            System.exit(64);
        }
        String base = args[0];
        String command = args[1];
        String body = Arrays.stream(args, 2, args.length).collect(Collectors.joining("\n"));
        String taskId = Arrays.stream(args, 2, args.length)
                .filter(a -> a.startsWith("task="))
                .map(a -> a.substring(5)).findFirst().orElse(null);

        Request request = switch (command) {
            case "local-stats" -> new Request("POST", "/api/task/local-stats", body);
            case "joint-create" -> new Request("POST", "/api/task/joint-stats", body);
            case "joint-run" -> new Request("POST", "/api/task/" + required(taskId) + "/run", body);
            case "status" -> new Request("GET", "/api/task/" + required(taskId), null);
            case "result" -> new Request("GET", "/api/task/" + required(taskId) + "/result", null);
            case "audit" -> new Request("GET", "/api/task/" + required(taskId) + "/audit", null);
            case "budget" -> new Request("GET", "/api/budget?" + body.replace('\n', '&'), null);
            case "approve-exc" -> new Request("POST",
                    "/api/task/" + required(taskId) + "/approve-exception", body);
            default -> throw new IllegalArgumentException("未知命令：" + command);
        };

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + request.path()));
        HttpRequest httpRequest = request.method().equals("POST")
                ? builder.POST(HttpRequest.BodyPublishers.ofString(request.body())).build()
                : builder.GET().build();
        HttpResponse<String> response = client.send(httpRequest,
                HttpResponse.BodyHandlers.ofString());
        System.out.print(response.body());
        System.exit(response.statusCode() == 200 ? 0 : 1);
    }

    private record Request(String method, String path, String body) {
    }

    private static String required(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("该命令需要 task=<id> 参数");
        }
        return taskId;
    }
}
