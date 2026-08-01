package com.ppcdemo.platform.core.budget;

import com.ppcdemo.platform.core.store.Database;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetCenterTest {

    private Database freshDb() {
        return Database.inMemory("budget-" + UUID.randomUUID());
    }

    @Test
    void 并发扣减不超卖() throws Exception {
        Database db = freshDb();
        BudgetCenter center = new BudgetCenter(db);
        center.ensureAccount("ds", 10.0);

        int threads = 8, attemptsPerThread = 5;   // 40 次 ×1.0 申请，只有 10 次能成功
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < attemptsPerThread; i++) {
                    try {
                        db.inTransaction(conn -> {
                            center.consumeInTx(conn, "ds", 1.0);
                            return null;
                        });
                        granted.incrementAndGet();
                    } catch (RuntimeException ignored) {
                        // 预算不足或锁冲突重试超时：均视为未授予
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertTrue(granted.get() <= 10, "授予次数不得超过预算总额：" + granted.get());
        assertEquals(10.0 - granted.get(), center.remaining("ds"), 1e-9, "扣减额与授予次数必须一致");
    }

    @Test
    void 开户幂等不重置消耗() {
        Database db = freshDb();
        BudgetCenter center = new BudgetCenter(db);
        center.ensureAccount("ds", 5.0);
        db.inTransaction(conn -> {
            center.consumeInTx(conn, "ds", 2.0);
            return null;
        });
        center.ensureAccount("ds", 5.0);
        assertEquals(3.0, center.remaining("ds"), 1e-9, "重复开户不得清零已消耗预算");
    }
}
