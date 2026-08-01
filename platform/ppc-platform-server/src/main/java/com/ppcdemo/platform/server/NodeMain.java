package com.ppcdemo.platform.server;

import java.nio.file.Path;

/**
 * 节点进程入口：java ... NodeMain <node.properties>
 * 启动控制面（mTLS）与管理面（loopback REST），阻塞运行；SIGTERM 优雅退出。
 */
public final class NodeMain {

    private NodeMain() {
    }

    public static void main(String[] args) throws Exception {
        NodeConfig config = NodeConfig.load(Path.of(args[0]));
        PlatformNode node = new PlatformNode(config);
        ManagementServer mgmt = new ManagementServer(node, config.mgmtPort());
        System.out.printf("节点 [%s] 就绪：控制面(mTLS)=:%d 管理面(loopback)=:%d peer=%s%n",
                config.party(), node.controlPort(), mgmt.port(), config.peerBaseUrl());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mgmt.close();
            node.close();
            System.out.println("节点已退出");
        }));
        Thread.currentThread().join();
    }
}
