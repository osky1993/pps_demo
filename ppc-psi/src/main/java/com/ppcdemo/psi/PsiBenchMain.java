package com.ppcdemo.psi;

import com.ppcdemo.common.config.PocConfig;
import com.ppcdemo.common.datagen.IdSetGenerator;
import com.ppcdemo.common.metrics.CsvReporter;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PoC-1 基准入口（loopback：单进程内存 RPC，排除网络因素的计算上限）。
 * 用法：java --enable-preview --add-modules jdk.incubator.vector ... PsiBenchMain [poc.yaml] [协议覆盖]
 * L 档注意 -Xmx（M1 设计文档 §3.4 内存档案）。
 */
public final class PsiBenchMain {

    private PsiBenchMain() {
    }

    public static void main(String[] args) throws Exception {
        Path yaml = Path.of(args.length > 0 ? args[0] : "poc.yaml");
        PocConfig config = PocConfig.load(yaml);
        String protocol = args.length > 1 ? args[1] : config.psi().protocol();
        int size = config.dataset().size();
        CsvReporter reporter = new CsvReporter(Path.of(config.bench().outputCsv()));
        String dataset = config.dataset().profile() + "档";

        System.out.printf("PSI 基准：protocol=%s, %s(%d×%d), 交集比例=%.2f%n",
                protocol, dataset, size, size, config.dataset().intersectionRatio());

        System.out.println("生成数据…");
        var sets = IdSetGenerator.generate(config.dataset().seed(), size, size,
                config.dataset().intersectionRatio());

        System.out.println("求交…");
        var result = LocalPsiRunner.run(protocol, sets.partyA(), sets.partyB());

        Set<Long> truth = Arrays.stream(sets.intersection()).boxed().collect(Collectors.toSet());
        if (!truth.equals(result.intersection())) {
            throw new IllegalStateException("交集与构造真值不一致！");
        }

        long totalKb = (result.serverSentBytes() + result.clientSentBytes()) / 1024;
        System.out.printf("完成：%d ms，交集 %d 条（校验通过），通信 server=%dKB client=%dKB 合计=%.1fMB%n",
                result.elapsedMillis(), result.intersection().size(),
                result.serverSentBytes() / 1024, result.clientSentBytes() / 1024, totalKb / 1024.0);
        reporter.report("psi-" + protocol, dataset, "loopback", "online", "elapsed", result.elapsedMillis(), "ms");
        reporter.report("psi-" + protocol, dataset, "loopback", "online", "trafficTotal", totalKb, "KB");
    }
}
