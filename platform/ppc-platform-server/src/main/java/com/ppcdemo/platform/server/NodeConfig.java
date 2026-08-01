package com.ppcdemo.platform.server;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/**
 * 节点配置（properties 文件）。样例见 deploy/node-server-a.properties。
 *
 * party=A
 * dbPath=mem                          # mem | 文件路径
 * controlPort=18001                   # 控制面 mTLS 端口（0=随机）
 * mgmtPort=17001                     # 管理面 REST 端口（仅绑 loopback，0=随机）
 * peerBaseUrl=https://peer-host:18002
 * keyStore=deploy/certs/nodeA.p12
 * trustStore=deploy/certs/nodeA-trust.p12
 * storePass=changeit
 * datasetAllowlist=ds://A/customers,ds://A/tx   # 响应方审批策略：允许参与协作的本方数据集
 * budgetDefault=10.0
 * engineTimeoutSeconds=600
 */
public record NodeConfig(String party, String dbPath, int controlPort, int mgmtPort,
                         String peerBaseUrl, Path keyStore, Path trustStore, String storePass,
                         Set<String> datasetAllowlist, double budgetDefault,
                         long engineTimeoutSeconds) {

    public static NodeConfig load(Path file) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取节点配置失败：" + file, e);
        }
        return new NodeConfig(
                require(p, "party"),
                p.getProperty("dbPath", "mem"),
                Integer.parseInt(p.getProperty("controlPort", "0")),
                Integer.parseInt(p.getProperty("mgmtPort", "0")),
                require(p, "peerBaseUrl"),
                Path.of(require(p, "keyStore")),
                Path.of(require(p, "trustStore")),
                p.getProperty("storePass", "changeit"),
                Set.of(p.getProperty("datasetAllowlist", "").split(",")),
                Double.parseDouble(p.getProperty("budgetDefault", "10.0")),
                Long.parseLong(p.getProperty("engineTimeoutSeconds", "600")));
    }

    private static String require(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("节点配置缺少字段：" + key);
        }
        return value;
    }
}
