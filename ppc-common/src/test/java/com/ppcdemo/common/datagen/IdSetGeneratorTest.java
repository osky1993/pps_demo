package com.ppcdemo.common.datagen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class IdSetGeneratorTest {

    @Test
    void 交集为构造的已知真值() {
        var sets = IdSetGenerator.generate(20260801L, 10_000, 10_000, 0.5);

        Set<Long> a = Arrays.stream(sets.partyA()).boxed().collect(Collectors.toSet());
        Set<Long> b = Arrays.stream(sets.partyB()).boxed().collect(Collectors.toSet());
        assertEquals(10_000, a.size(), "A 方 ID 必须全局唯一");
        assertEquals(10_000, b.size(), "B 方 ID 必须全局唯一");
        assertEquals(5_000, sets.intersection().length);

        Set<Long> actual = new HashSet<>(a);
        actual.retainAll(b);
        Set<Long> truth = Arrays.stream(sets.intersection()).boxed().collect(Collectors.toSet());
        assertEquals(truth, actual, "实际交集必须与构造真值一致");
    }

    @Test
    void 固定种子可复现() {
        var first = IdSetGenerator.generate(42L, 1_000, 2_000, 0.3);
        var second = IdSetGenerator.generate(42L, 1_000, 2_000, 0.3);
        assertArrayEquals(first.partyA(), second.partyA());
        assertArrayEquals(first.partyB(), second.partyB());
        assertArrayEquals(first.intersection(), second.intersection());
    }
}
