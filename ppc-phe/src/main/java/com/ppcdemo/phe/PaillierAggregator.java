package com.ppcdemo.phe;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPrivateKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Paillier 密文聚合（PoC-2 核心）。金额编码规范：分为单位的 long（见 ValueGenerator）。
 *
 * 关于混淆（obfuscation）：javallier 的 encrypt 产出的密文未混淆（g^m 快速路径），
 * 发送给对方前必须 obfuscate（乘 r^n 盲化），否则密文可被字典攻击还原。
 * 两者性能差一个数量级以上，基准分开计量（M1 设计文档 §4.2）。
 */
public final class PaillierAggregator implements AutoCloseable {

    private final PaillierPrivateKey privateKey;
    private final PaillierContext context;
    private final ExecutorService pool;
    private final int parallelism;

    public PaillierAggregator(int keyBits, int parallelism) {
        this.privateKey = PaillierPrivateKey.create(keyBits);
        this.context = privateKey.getPublicKey().createSignedContext();
        this.parallelism = parallelism;
        this.pool = Executors.newFixedThreadPool(parallelism);
    }

    /** 逐条加密（不混淆，g^m 快速路径），分片并行。 */
    public EncryptedNumber[] encryptAll(long[] valuesCents) {
        return parallelMap(valuesCents.length, i -> context.encrypt(valuesCents[i]));
    }

    /** 对密文数组盲化（发送前必做），分片并行。 */
    public EncryptedNumber[] obfuscateAll(EncryptedNumber[] ciphertexts) {
        return parallelMap(ciphertexts.length, i -> ciphertexts[i].obfuscate());
    }

    /** 密文累加（纯模乘，单线程即可）。 */
    public EncryptedNumber sum(EncryptedNumber[] ciphertexts) {
        EncryptedNumber acc = ciphertexts[0];
        for (int i = 1; i < ciphertexts.length; i++) {
            acc = acc.add(ciphertexts[i]);
        }
        return acc;
    }

    public long decryptToLong(EncryptedNumber ciphertext) {
        return privateKey.decrypt(ciphertext).decodeLong();
    }

    /** 序列化后单密文字节数（估算跨方传输量用）。 */
    public int ciphertextBytes(EncryptedNumber ciphertext) {
        return ciphertext.calculateCiphertext().toByteArray().length;
    }

    private interface IndexedOp {
        EncryptedNumber apply(int index);
    }

    private EncryptedNumber[] parallelMap(int n, IndexedOp op) {
        EncryptedNumber[] out = new EncryptedNumber[n];
        int chunk = Math.max(1, (n + parallelism - 1) / parallelism);
        List<Future<?>> futures = new ArrayList<>(parallelism);
        for (int p = 0; p < parallelism; p++) {
            int from = p * chunk;
            int to = Math.min(n, from + chunk);
            futures.add(pool.submit(() -> {
                for (int i = from; i < to; i++) {
                    out[i] = op.apply(i);
                }
            }));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并行加密被中断", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("并行加密失败", e.getCause());
            }
        }
        return out;
    }

    @Override
    public void close() {
        pool.shutdown();
    }

    /** 明文求和参照（校验用）。 */
    public static long plainSum(long[] values) {
        return Arrays.stream(values).sum();
    }
}
