package com.ppcdemo.engine.psi;

import com.ppcdemo.psi.NettyPsiRunner;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PSI 引擎进程入口（进程一任务）：job 文件（Properties）驱动，退出码即结果状态。
 * 0=成功；2=协议/参数错误；3=IO 错误。
 * client 侧把交集逐行写入 outputFile；server 侧不产出文件。
 */
public final class PsiEngineMain {

    private PsiEngineMain() {
    }

    public static void main(String[] args) {
        try {
            Properties job = new Properties();
            job.load(new StringReader(Files.readString(Path.of(args[0]))));

            String role = require(job, "role");
            String protocol = require(job, "protocol");
            var local = NettyPsiRunner.Endpoint.parse(require(job, "local"));
            var peer = NettyPsiRunner.Endpoint.parse(require(job, "peer"));
            long[] ids = readIds(Path.of(require(job, "idsFile")));
            int peerSize = Integer.parseInt(require(job, "peerSize"));

            if ("server".equals(role)) {
                var result = NettyPsiRunner.runServer(protocol, ids, peerSize, local, peer);
                System.out.printf("engine[server] done: %d ms, sent %d B%n",
                        result.elapsedMillis(), result.sentBytes());
            } else {
                var result = NettyPsiRunner.runClient(protocol, ids, peerSize, local, peer);
                Path output = Path.of(require(job, "outputFile"));
                Set<String> lines = result.intersection().stream()
                        .map(String::valueOf).collect(Collectors.toSet());
                Files.writeString(output, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
                System.out.printf("engine[client] done: %d ms, |I|=%d, sent %d B%n",
                        result.elapsedMillis(), result.intersection().size(), result.sentBytes());
            }
            System.exit(0);
        } catch (IOException e) {
            System.err.println("engine IO error: " + e);
            System.exit(3);
        } catch (Exception e) {
            System.err.println("engine error: " + e);
            System.exit(2);
        }
    }

    private static String require(Properties job, String key) {
        String value = job.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("job 缺少字段：" + key);
        }
        return value;
    }

    private static long[] readIds(Path file) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.filter(l -> !l.isBlank()).mapToLong(Long::parseLong).toArray();
        }
    }
}
