package com.ppcdemo.phe;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPrivateKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledPaillierEncryptorTest {

    @Test
    void 池化加密与javallier解密互通含负数与密文加法() throws Exception {
        PaillierPrivateKey privateKey = PaillierPrivateKey.create(2048);
        PaillierContext context = privateKey.getPublicKey().createSignedContext();
        try (ObfuscationFactorPool pool = new ObfuscationFactorPool(privateKey.getPublicKey(), 64, 2)) {
            PooledPaillierEncryptor encryptor = new PooledPaillierEncryptor(context, pool);

            long[] samples = {0, 1, -1, 42_00, -3_000_00, Integer.MAX_VALUE};
            for (long v : samples) {
                assertEquals(v, privateKey.decrypt(encryptor.encryptForTransfer(v)).decodeLong(),
                        "池化加密解密必须与明文一致：" + v);
            }
            // 池化密文与 javallier 原生密文可混合运算
            EncryptedNumber mixed = encryptor.encryptForTransfer(100).add(context.encrypt(23));
            assertEquals(123, privateKey.decrypt(mixed).decodeLong());
        }
    }

    @Test
    void 池化在线吞吐显著高于同步盲化() throws Exception {
        PaillierPrivateKey privateKey = PaillierPrivateKey.create(2048);
        PaillierContext context = privateKey.getPublicKey().createSignedContext();
        int n = 2_000;
        try (ObfuscationFactorPool pool = new ObfuscationFactorPool(privateKey.getPublicKey(), n, 4)) {
            // 等池预填充满（离线阶段）
            long deadline = System.currentTimeMillis() + 120_000;
            while (pool.size() < n && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(n, pool.size(), "池应在离线阶段填满");

            PooledPaillierEncryptor encryptor = new PooledPaillierEncryptor(context, pool);
            long begin = System.nanoTime();
            for (int i = 0; i < n; i++) {
                encryptor.encryptForTransfer(i);
            }
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
            double perSec = n * 1000.0 / Math.max(1, elapsedMs);
            System.out.printf("池化在线加密吞吐：%.0f 条/秒（n=%d, %d ms），降级次数=%d%n",
                    perSec, n, elapsedMs, pool.syncFallbackCount());
            assertEquals(0, pool.syncFallbackCount(), "满池顺序取用不应触发同步降级");
            // M1 同步盲化实测 945 条/秒；池化在线路径至少应高一个数量级
            assertTrue(perSec > 9_450, "池化吞吐未达同步盲化 10 倍：" + perSec);
        }
    }
}
