package com.ppcdemo.platform.core.connector;

/**
 * 数据接入抽象（M2 §4.6 F6）。ref 格式：
 *   csv:/abs/path.csv?idCol=0&valueCol=1        （valueCol 可省=纯 ID 集）
 *   jdbc:<jdbcUrl>?user=..&password=..#<SQL>    （SQL 第 1 列 ID，第 2 列金额（元），可无第 2 列）
 */
public interface DatasetConnector {

    boolean supports(String ref);

    Dataset load(String ref);

    /** 按 ref 前缀路由到具体 connector。 */
    static Dataset resolve(String ref, DatasetConnector... connectors) {
        for (DatasetConnector connector : connectors) {
            if (connector.supports(ref)) {
                return connector.load(ref);
            }
        }
        throw new IllegalArgumentException("无 connector 支持该数据集引用：" + ref);
    }
}
