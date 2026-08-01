package com.ppcdemo.phe;

import com.n1analytics.paillier.EncryptedNumber;
import com.ppcdemo.common.datagen.ValueGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PaillierAggregatorTest {

    @Test
    void 万条并行加密聚合与明文求和一致() {
        long[] values = ValueGenerator.logNormalCents(20260801L, 10_000, 100_000);
        try (PaillierAggregator aggregator = new PaillierAggregator(2048, 4)) {
            EncryptedNumber[] ciphertexts = aggregator.encryptAll(values);
            long decrypted = aggregator.decryptToLong(aggregator.sum(ciphertexts));
            assertEquals(PaillierAggregator.plainSum(values), decrypted);
        }
    }

    @Test
    void 盲化不改变解密结果但改变密文() {
        try (PaillierAggregator aggregator = new PaillierAggregator(2048, 2)) {
            EncryptedNumber[] raw = aggregator.encryptAll(new long[]{42L});
            EncryptedNumber[] obfuscated = aggregator.obfuscateAll(raw);
            assertEquals(42L, aggregator.decryptToLong(obfuscated[0]), "盲化不得改变明文语义");
            assertNotEquals(raw[0].calculateCiphertext(), obfuscated[0].calculateCiphertext(),
                    "盲化后密文必须不同（r^n 随机因子）");
        }
    }
}
