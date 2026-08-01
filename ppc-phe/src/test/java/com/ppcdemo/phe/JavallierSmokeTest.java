package com.ppcdemo.phe;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPrivateKey;
import com.n1analytics.paillier.PaillierPublicKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W1 冒烟：验证 2017 年的 javallier 在 JDK 17+ 上是否可用（M1 设计文档 §2.2 风险 R2）。
 * 通过 = H2 的前置条件成立；失败 = 触发 §4.3 备份方案评审。
 */
class JavallierSmokeTest {

    @Test
    void 密文加法与明文求和一致() {
        PaillierPrivateKey privateKey = PaillierPrivateKey.create(2048);
        PaillierPublicKey publicKey = privateKey.getPublicKey();
        PaillierContext context = publicKey.createSignedContext();

        // 金额编码规范：分为单位的 long
        long[] amountsCents = {12_50, 99_99, 3_000_00, 1};
        EncryptedNumber sum = context.encrypt(amountsCents[0]);
        for (int i = 1; i < amountsCents.length; i++) {
            sum = sum.add(context.encrypt(amountsCents[i]));
        }

        long expected = 12_50 + 99_99 + 3_000_00 + 1;
        assertEquals(expected, privateKey.decrypt(sum).decodeLong());
    }
}
