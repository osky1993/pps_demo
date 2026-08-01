package com.ppcdemo.platform.core.keyvault;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPrivateKey;
import com.n1analytics.paillier.PaillierPublicKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久化任务级密钥保险箱（M2.5 #4）：密钥材料 (n, totient) 信封加密后落盘，
 * 节点重启后可恢复（解决 EphemeralKeyVault「重启即密钥消亡」）。
 * 素数自行生成（javallier 公开构造器 PaillierPrivateKey(publicKey, totient) 重建），
 * 私钥对象仍不出本类；destroy 同步清除内存与磁盘。
 */
public final class PersistentKeyVault implements KeyVault {

    private final Path directory;
    private final EnvelopeEncryptor envelope;
    private final Map<String, PaillierPrivateKey> cache = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public PersistentKeyVault(Path directory, EnvelopeEncryptor envelope) {
        this.directory = directory;
        this.envelope = envelope;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("密钥目录创建失败：" + directory, e);
        }
    }

    @Override
    public byte[] createTaskKey(String taskId, int keyBits) {
        PaillierPrivateKey key = cache.computeIfAbsent(taskId, id -> {
            PaillierPrivateKey loaded = tryLoad(id);
            return loaded != null ? loaded : generateAndPersist(id, keyBits);
        });
        return key.getPublicKey().getModulus().toByteArray();
    }

    @Override
    public long decrypt(String taskId, byte[] cipherPayload) {
        PaillierPrivateKey key = cache.computeIfAbsent(taskId, this::tryLoad);
        if (key == null) {
            cache.remove(taskId);
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
        cache.remove(taskId);
        try {
            Files.deleteIfExists(keyFile(taskId));
        } catch (IOException e) {
            throw new UncheckedIOException("密钥文件删除失败：" + taskId, e);
        }
    }

    private PaillierPrivateKey generateAndPersist(String taskId, int keyBits) {
        BigInteger p = BigInteger.probablePrime(keyBits / 2, random);
        BigInteger q;
        do {
            q = BigInteger.probablePrime(keyBits / 2, random);
        } while (p.equals(q));
        BigInteger n = p.multiply(q);
        BigInteger totient = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
        PaillierPrivateKey key = new PaillierPrivateKey(new PaillierPublicKey(n), totient);

        byte[] nBytes = n.toByteArray();
        byte[] tBytes = totient.toByteArray();
        byte[] material = ByteBuffer.allocate(8 + nBytes.length + tBytes.length)
                .putInt(nBytes.length).put(nBytes)
                .putInt(tBytes.length).put(tBytes)
                .array();
        try {
            Files.write(keyFile(taskId), envelope.seal(material));
        } catch (IOException e) {
            throw new UncheckedIOException("密钥落盘失败：" + taskId, e);
        }
        return key;
    }

    private PaillierPrivateKey tryLoad(String taskId) {
        Path file = keyFile(taskId);
        if (Files.notExists(file)) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(envelope.unseal(Files.readAllBytes(file)));
            byte[] nBytes = new byte[buffer.getInt()];
            buffer.get(nBytes);
            byte[] tBytes = new byte[buffer.getInt()];
            buffer.get(tBytes);
            return new PaillierPrivateKey(new PaillierPublicKey(new BigInteger(nBytes)),
                    new BigInteger(tBytes));
        } catch (IOException e) {
            throw new UncheckedIOException("密钥加载失败：" + taskId, e);
        }
    }

    private Path keyFile(String taskId) {
        if (!taskId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("非法 taskId（防路径穿越）：" + taskId);
        }
        return directory.resolve(taskId + ".key");
    }
}
