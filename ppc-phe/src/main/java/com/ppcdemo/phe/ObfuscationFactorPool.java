package com.ppcdemo.phe;

import com.n1analytics.paillier.PaillierPublicKey;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 盲化因子预计算池（S0 遗留项 4，M2 约束 C4 缓解路径 2）。
 *
 * 原理：Paillier 盲化因子 r^n mod n² 与消息无关，可离线预生成；
 * 在线加密只剩「g=n+1 快速加密（一次模乘级）+ 池中因子一次模乘」，
 * 把 945 条/秒的在线盲化吞吐提升到万级。
 *
 * 池耗尽时降级为同步计算（计数暴露给监控，M2 F11 告警项）。
 */
public final class ObfuscationFactorPool implements AutoCloseable {

    private final BigInteger modulus;
    private final BigInteger modulusSquared;
    private final BlockingQueue<BigInteger> pool;
    private final Thread[] producers;
    private final AtomicLong syncFallbackCount = new AtomicLong();
    private volatile boolean closed;

    public ObfuscationFactorPool(PaillierPublicKey publicKey, int capacity, int producerThreads) {
        this.modulus = publicKey.getModulus();
        this.modulusSquared = publicKey.getModulusSquared();
        this.pool = new ArrayBlockingQueue<>(capacity);
        this.producers = new Thread[producerThreads];
        for (int i = 0; i < producerThreads; i++) {
            producers[i] = new Thread(this::produceLoop, "obf-pool-" + i);
            producers[i].setDaemon(true);
            producers[i].start();
        }
    }

    /** 取一个因子；池空则同步计算（降级路径，计数用于告警）。 */
    public BigInteger take() {
        BigInteger factor = pool.poll();
        if (factor != null) {
            return factor;
        }
        syncFallbackCount.incrementAndGet();
        return computeFactor(new SecureRandom());
    }

    public int size() {
        return pool.size();
    }

    public long syncFallbackCount() {
        return syncFallbackCount.get();
    }

    private void produceLoop() {
        SecureRandom random = new SecureRandom();
        while (!closed) {
            try {
                pool.put(computeFactor(random));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private BigInteger computeFactor(SecureRandom random) {
        // r ∈ [1, n)，与 n 互素的概率压倒性（n 为两大素数之积）
        BigInteger r;
        do {
            r = new BigInteger(modulus.bitLength(), random).mod(modulus);
        } while (r.signum() == 0);
        return r.modPow(modulus, modulusSquared);
    }

    @Override
    public void close() {
        closed = true;
        for (Thread producer : producers) {
            producer.interrupt();
        }
    }
}
