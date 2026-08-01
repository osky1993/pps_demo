package com.ppcdemo.phe;

import com.n1analytics.paillier.EncryptedNumber;
import com.ppcdemo.common.config.PocConfig;
import com.ppcdemo.common.datagen.ValueGenerator;
import com.ppcdemo.common.metrics.CsvReporter;
import com.ppcdemo.common.metrics.StopWatchRecorder;

import java.nio.file.Path;

/**
 * PoC-2 基准入口：keygen → encrypt(并行) → obfuscate(并行) → 密文累加 → decrypt → 校验。
 * 用法：java ... PheBenchMain [poc.yaml 路径] [条数覆盖]
 * 结果落 bench CSV（scenario=phe），吞吐打印到控制台。
 */
public final class PheBenchMain {

    private PheBenchMain() {
    }

    public static void main(String[] args) throws Exception {
        Path yaml = Path.of(args.length > 0 ? args[0] : "poc.yaml");
        PocConfig config = PocConfig.load(yaml);
        int n = args.length > 1 ? Integer.parseInt(args[1]) : config.dataset().size();
        int keyBits = config.phe().keyBits();
        int parallelism = config.phe().parallelism();
        CsvReporter reporter = new CsvReporter(Path.of(config.bench().outputCsv()));
        String dataset = n + "条";

        System.out.printf("PHE 基准：n=%d, keyBits=%d, parallelism=%d%n", n, keyBits, parallelism);
        long[] values = ValueGenerator.logNormalCents(config.dataset().seed(), n, config.dp().clampUpper());

        StopWatchRecorder watch = new StopWatchRecorder();
        watch.start("keygen");
        try (PaillierAggregator aggregator = new PaillierAggregator(keyBits, parallelism)) {
            watch.stop("keygen");

            watch.start("encrypt");
            EncryptedNumber[] ciphertexts = aggregator.encryptAll(values);
            watch.stop("encrypt");

            watch.start("obfuscate");
            EncryptedNumber[] obfuscated = aggregator.obfuscateAll(ciphertexts);
            watch.stop("obfuscate");

            watch.start("sum");
            EncryptedNumber sum = aggregator.sum(obfuscated);
            watch.stop("sum");

            watch.start("decrypt");
            long decrypted = aggregator.decryptToLong(sum);
            watch.stop("decrypt");

            long expected = PaillierAggregator.plainSum(values);
            if (decrypted != expected) {
                throw new IllegalStateException("解密结果与明文求和不一致：" + decrypted + " != " + expected);
            }

            int bytesPer = aggregator.ciphertextBytes(obfuscated[0]);
            watch.allMillis().forEach((phase, millis) -> {
                reporter.report("phe", dataset, "local", phase, "elapsed", millis, "ms");
                System.out.printf("  %-9s %6d ms%s%n", phase, millis,
                        throughputNote(phase, n, millis));
            });
            reporter.report("phe", dataset, "local", "transfer", "ciphertextBytes", bytesPer, "B/条");
            System.out.printf("  校验通过：Σ=%d（分）；单密文 %d B，n 条合计约 %.1f MB%n",
                    decrypted, bytesPer, n * (double) bytesPer / 1024 / 1024);
        }
    }

    private static String throughputNote(String phase, int n, long millis) {
        if (millis == 0 || !(phase.equals("encrypt") || phase.equals("obfuscate") || phase.equals("sum"))) {
            return "";
        }
        return String.format("  (%.0f 条/秒)", n * 1000.0 / millis);
    }
}
