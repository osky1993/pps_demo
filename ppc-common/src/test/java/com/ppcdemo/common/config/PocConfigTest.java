package com.ppcdemo.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PocConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void 加载完整配置() throws IOException {
        Path yaml = tempDir.resolve("poc.yaml");
        Files.writeString(yaml, """
                party:
                  role: client
                  local: 0.0.0.0:9002
                  peer: 192.168.1.12:9001
                dataset:
                  profile: M
                  seed: 20260801
                  intersectionRatio: 0.30
                psi:
                  protocol: kkrt16
                phe:
                  keyBits: 3072
                  parallelism: 4
                dp:
                  epsilon: 0.1
                  clampLower: 0
                  clampUpper: 100000
                  budgetTotal: 10.0
                bench:
                  repeat: 5
                  outputCsv: ./bench-result.csv
                """);
        PocConfig config = PocConfig.load(yaml);
        assertEquals("client", config.party().role());
        assertEquals(1_000_000, config.dataset().size());
        assertEquals("kkrt16", config.psi().protocol());
        assertEquals(3072, config.phe().keyBits());
        assertEquals(0.1, config.dp().epsilon());
        assertEquals(5, config.bench().repeat());
    }

    @Test
    void 缺省项取默认值() throws IOException {
        Path yaml = tempDir.resolve("minimal.yaml");
        Files.writeString(yaml, "dataset:\n  profile: S\n");
        PocConfig config = PocConfig.load(yaml);
        assertEquals("server", config.party().role());
        assertEquals(10_000, config.dataset().size());
        assertEquals(2048, config.phe().keyBits());
        assertEquals(1.0, config.dp().epsilon());
    }
}
