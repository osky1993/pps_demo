package com.ppcdemo.platform.core.keyvault;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPrivateKey;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内任务级密钥保险箱。私钥对象不离开本类。 */
public final class EphemeralKeyVault implements KeyVault {

    private final Map<String, PaillierPrivateKey> keys = new ConcurrentHashMap<>();

    @Override
    public byte[] createTaskKey(String taskId, int keyBits) {
        PaillierPrivateKey key = keys.computeIfAbsent(taskId,
                id -> PaillierPrivateKey.create(keyBits));
        return key.getPublicKey().getModulus().toByteArray();
    }

    @Override
    public long decrypt(String taskId, byte[] cipherPayload) {
        PaillierPrivateKey key = keys.get(taskId);
        if (key == null) {
            throw new IllegalStateException("任务密钥不存在（未创建或已销毁）：" + taskId);
        }
        ByteBuffer buffer = ByteBuffer.wrap(cipherPayload);
        int exponent = buffer.getInt();
        byte[] cipherBytes = new byte[buffer.remaining()];
        buffer.get(cipherBytes);
        PaillierContext context = key.getPublicKey().createSignedContext();
        return key.decrypt(new EncryptedNumber(context, new BigInteger(cipherBytes), exponent, true))
                .decodeLong();
    }

    @Override
    public void destroy(String taskId) {
        keys.remove(taskId);
    }
}
