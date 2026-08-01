package com.ppcdemo.psi;

import com.ppcdemo.common.datagen.IdSetGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W1/W2 冒烟：S 档（1 万 × 1 万，交集 50%）双协议求交，
 * 输出与 IdSetGenerator 构造真值全量比对，不抽样（M1 设计文档 §3.4）。
 */
class LocalPsiRunnerTest {

    @ParameterizedTest
    @ValueSource(strings = {"rr22", "kkrt16"})
    void S档交集与真值一致(String protocol) throws Exception {
        var sets = IdSetGenerator.generate(20260801L, 10_000, 10_000, 0.5);

        var result = LocalPsiRunner.run(protocol, sets.partyA(), sets.partyB());

        Set<Long> truth = Arrays.stream(sets.intersection()).boxed().collect(Collectors.toSet());
        assertEquals(truth, result.intersection(),
                protocol + " 交集必须与构造真值一致");
        System.out.printf("[%s] S档: %dms, server发送 %dKB, client发送 %dKB%n",
                protocol, result.elapsedMillis(),
                result.serverSentBytes() / 1024, result.clientSentBytes() / 1024);
    }
}
