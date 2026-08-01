package com.ppcdemo.psi;

import com.ppcdemo.common.datagen.IdSetGenerator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchedRr22PsiTest {

    @Test
    void 分批并集与单次求交等价() throws Exception {
        var sets = IdSetGenerator.generate(7L, 10_000, 10_000, 0.5);
        Set<Long> truth = Arrays.stream(sets.intersection()).boxed().collect(Collectors.toSet());

        // 强制 3000/批 → 4 批，验证跨批并集正确性
        var batched = BatchedRr22Psi.run(sets.partyA(), sets.partyB(), 3_000);

        assertEquals(4, batched.batchCount());
        assertEquals(truth, batched.intersection(), "分批并集必须与构造真值一致");
    }
}
