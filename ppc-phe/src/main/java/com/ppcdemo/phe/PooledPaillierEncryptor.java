package com.ppcdemo.phe;

import com.n1analytics.paillier.EncodedNumber;
import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPublicKey;

import java.math.BigInteger;

/**
 * 走预计算池的 Paillier 加密器（M2 §4.3 encryptForTransfer 的实现内核）。
 *
 * 快速路径依据：javallier 的 g = n+1，此时 g^v mod n² = 1 + v·n mod n²（免模幂）；
 * 盲化 = 乘池中预生成的 r^n mod n²。产物以 isSafe=true 构造，序列化不会二次盲化。
 * 正确性由测试与 context.encrypt 路径交叉验证。
 */
public final class PooledPaillierEncryptor {

    private final PaillierContext context;
    private final BigInteger modulus;
    private final BigInteger modulusSquared;
    private final ObfuscationFactorPool pool;

    public PooledPaillierEncryptor(PaillierContext context, ObfuscationFactorPool pool) {
        PaillierPublicKey publicKey = context.getPublicKey();
        if (!publicKey.getGenerator().equals(publicKey.getModulus().add(BigInteger.ONE))) {
            throw new IllegalStateException("快速路径要求 g = n+1（javallier 默认），当前公钥不满足");
        }
        this.context = context;
        this.modulus = publicKey.getModulus();
        this.modulusSquared = publicKey.getModulusSquared();
        this.pool = pool;
    }

    /** 加密并盲化（出域安全）：一次编码 + 两次模乘，无在线模幂。 */
    public EncryptedNumber encryptForTransfer(long value) {
        EncodedNumber encoded = context.encode(value);
        BigInteger raw = BigInteger.ONE
                .add(encoded.getValue().multiply(modulus))
                .mod(modulusSquared);
        BigInteger safe = raw.multiply(pool.take()).mod(modulusSquared);
        return new EncryptedNumber(context, safe, encoded.getExponent(), true);
    }
}
