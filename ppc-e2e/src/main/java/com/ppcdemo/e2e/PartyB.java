package com.ppcdemo.e2e;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPublicKey;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;

/**
 * B 方（电商）：数据方。持有 用户ID → 年消费额（分）明细。
 * 全程可见：自己的明细、A 方公钥、交集；不可见：A 方私钥、解密结果、加噪结果。
 * 只对外发送一条盲化后的密文Σ —— 单条明细的密文从不出域，
 * 因此逐条盲化不必要，只需盲化最终Σ（这一点消解了 PHE 基准发现的逐条盲化瓶颈）。
 */
public final class PartyB {

    private final Map<Long, Long> amountCentsById;
    private PaillierContext peerContext;

    public PartyB(Map<Long, Long> amountCentsById) {
        this.amountCentsById = amountCentsById;
    }

    public Set<Long> ids() {
        return amountCentsById.keySet();
    }

    public void receivePublicKey(byte[] modulusBytes) {
        PaillierPublicKey publicKey = new PaillierPublicKey(new BigInteger(modulusBytes));
        this.peerContext = publicKey.createSignedContext();
    }

    /**
     * 对交集用户的消费额做密态求和，仅盲化最终Σ后序列化发出。
     * 格式：[4字节 exponent][ciphertext 大整数]。
     */
    public byte[] encryptedSumOf(Set<Long> intersectionIds) {
        if (peerContext == null) {
            throw new IllegalStateException("尚未收到 A 方公钥");
        }
        EncryptedNumber sum = null;
        for (long id : intersectionIds) {
            Long amount = amountCentsById.get(id);
            if (amount == null) {
                throw new IllegalArgumentException("交集中的 ID 不在本方数据集：" + id);
            }
            EncryptedNumber encrypted = peerContext.encrypt(amount);
            sum = sum == null ? encrypted : sum.add(encrypted);
        }
        if (sum == null) {
            throw new IllegalStateException("交集为空，无可聚合数据");
        }
        EncryptedNumber obfuscated = sum.obfuscate();
        byte[] cipherBytes = obfuscated.calculateCiphertext().toByteArray();
        return ByteBuffer.allocate(4 + cipherBytes.length)
                .putInt(obfuscated.getExponent())
                .put(cipherBytes)
                .array();
    }
}
