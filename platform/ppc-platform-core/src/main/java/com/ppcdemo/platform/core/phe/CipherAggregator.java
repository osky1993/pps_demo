package com.ppcdemo.platform.core.phe;

import com.n1analytics.paillier.EncodedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPublicKey;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * 数据方侧密态聚合（M2 §4.3 盲化策略的落地）：
 * 逐条 g=n+1 快速加密（免模幂）→ 同态累乘 → **仅对最终Σ盲化一次**（单条明细密文不出域，
 * M1 验证的关键设计），输出 [4字节 exponent=0][ciphertext] 载荷。
 * 所有值统一对齐到 exponent 0，规避 javallier 变长指数在原始域运算的对齐问题。
 */
public final class CipherAggregator {

    private CipherAggregator() {
    }

    public static byte[] aggregateForTransfer(byte[] publicKeyModulus, long[] valuesCents) {
        PaillierPublicKey publicKey = new PaillierPublicKey(new BigInteger(publicKeyModulus));
        PaillierContext context = publicKey.createSignedContext();
        BigInteger n = publicKey.getModulus();
        BigInteger nSquared = publicKey.getModulusSquared();

        BigInteger acc = BigInteger.ONE;   // exponent 0 下「0」的密文 = 1 + 0·n = 1
        for (long v : valuesCents) {
            EncodedNumber encoded = context.encode(v);
            if (encoded.getExponent() != 0) {
                encoded = encoded.decreaseExponentTo(0);
            }
            BigInteger cipher = BigInteger.ONE.add(encoded.getValue().multiply(n)).mod(nSquared);
            acc = acc.multiply(cipher).mod(nSquared);
        }
        // 出域前唯一一次盲化
        SecureRandom random = new SecureRandom();
        BigInteger r;
        do {
            r = new BigInteger(n.bitLength(), random).mod(n);
        } while (r.signum() == 0);
        acc = acc.multiply(r.modPow(n, nSquared)).mod(nSquared);

        byte[] cipherBytes = acc.toByteArray();
        return ByteBuffer.allocate(4 + cipherBytes.length).putInt(0).put(cipherBytes).array();
    }
}
