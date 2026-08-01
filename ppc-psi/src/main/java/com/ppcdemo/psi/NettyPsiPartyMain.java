package com.ppcdemo.psi;

import com.ppcdemo.common.config.PocConfig;
import com.ppcdemo.common.datagen.IdSetGenerator;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 双进程/双机 PSI 基准入口（S0 遗留项 1）。
 * 用法：java --enable-preview --add-modules jdk.incubator.vector ...
 *        NettyPsiPartyMain <poc.yaml> [协议覆盖]
 * 角色、端点、数据档位均取自 yaml：server 持 partyA 数据，client 持 partyB 数据；
 * 双方以相同 seed 各自生成数据（生成器确定性），client 侧对真值全量校验。
 */
public final class NettyPsiPartyMain {

    private NettyPsiPartyMain() {
    }

    public static void main(String[] args) throws Exception {
        PocConfig config = PocConfig.load(Path.of(args.length > 0 ? args[0] : "poc.yaml"));
        String protocol = args.length > 1 ? args[1] : config.psi().protocol();
        String role = config.party().role();
        int size = config.dataset().size();
        var local = NettyPsiRunner.Endpoint.parse(config.party().local());
        var peer = NettyPsiRunner.Endpoint.parse(config.party().peer());

        System.out.printf("[%s] netty PSI：protocol=%s, %s档(%d×%d), local=%s, peer=%s%n",
                role, protocol, config.dataset().profile(), size, size,
                config.party().local(), config.party().peer());

        var sets = IdSetGenerator.generate(config.dataset().seed(), size, size,
                config.dataset().intersectionRatio());

        if ("server".equals(role)) {
            var result = NettyPsiRunner.runServer(protocol, sets.partyA(), size, local, peer);
            System.out.printf("[server] 完成：%d ms，发送 %d KB%n",
                    result.elapsedMillis(), result.sentBytes() / 1024);
        } else {
            var result = NettyPsiRunner.runClient(protocol, sets.partyB(), size, local, peer);
            Set<Long> truth = Arrays.stream(sets.intersection()).boxed().collect(Collectors.toSet());
            if (!truth.equals(result.intersection())) {
                System.out.println("[client] 校验失败：交集与构造真值不一致！");
                System.exit(2);
            }
            System.out.printf("[client] 完成：%d ms，交集 %d 条（校验通过），发送 %d KB%n",
                    result.elapsedMillis(), result.intersection().size(), result.sentBytes() / 1024);
        }
    }
}
