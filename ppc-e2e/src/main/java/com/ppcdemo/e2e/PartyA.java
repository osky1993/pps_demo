package com.ppcdemo.e2e;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPrivateKey;
import com.ppcdemo.dp.DpAggregator;

import java.math.BigInteger;
import java.nio.ByteBuffer;

/**
 * A 方（银行）：出题方。持有客户 ID 集合与 Paillier 私钥。
 * 全程可见：自己的 ID、交集、密文Σ、解密后的真实总额（B 方明细永不可见）、加噪结果。
 */
public final class PartyA {

    private final PaillierPrivateKey privateKey;
    private final PaillierContext context;
    private final DpAggregator dpGateway;

    public PartyA(int keyBits, DpAggregator dpGateway) {
        this.privateKey = PaillierPrivateKey.create(keyBits);
        this.context = privateKey.getPublicKey().createSignedContext();
        this.dpGateway = dpGateway;
    }

    /** 公钥序列化为模数字节（B 方据此重建公钥）。 */
    public byte[] publicKeyBytes() {
        return privateKey.getPublicKey().getModulus().toByteArray();
    }

    /** 解密 B 方发来的密文Σ。格式：[4字节 exponent][ciphertext 大整数]。 */
    public long decryptSum(byte[] ciphertextPayload) {
        ByteBuffer buffer = ByteBuffer.wrap(ciphertextPayload);
        int exponent = buffer.getInt();
        byte[] cipherBytes = new byte[buffer.remaining()];
        buffer.get(cipherBytes);
        EncryptedNumber encrypted = new EncryptedNumber(context, new BigInteger(cipherBytes), exponent, true);
        return privateKey.decrypt(encrypted).decodeLong();
    }

    /** 出库唯一通道：真实值必须经 DP 网关（预算扣减 + 加噪）后才能对外。 */
    public double noisedForRelease(String dataset, long trueSumCents, double epsilon, long clampUpper) {
        return dpGateway.noisedPreAggregatedSum(dataset, trueSumCents, epsilon, clampUpper);
    }
}
