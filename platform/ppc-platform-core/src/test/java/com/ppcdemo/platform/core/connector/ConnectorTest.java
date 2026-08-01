package com.ppcdemo.platform.core.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorTest {

    @TempDir
    Path dir;

    @Test
    void CSV接入含表头与金额编码() throws Exception {
        Path csv = dir.resolve("orders.csv");
        Files.writeString(csv, """
                user_id,amount_yuan
                1001,12.50
                1002,99.99
                1003,3000.00
                """);
        Dataset ds = new CsvConnector().load("csv:" + csv + "?idCol=0&valueCol=1");
        assertArrayEquals(new long[]{1001, 1002, 1003}, sorted(ds.ids()));
        assertEquals(1250L, ds.amountCentsById().get(1001L), "12.50 元必须编码为 1250 分");
        assertEquals(9999L, ds.amountCentsById().get(1002L));
    }

    @Test
    void 指纹对内容敏感且可复现() throws Exception {
        Path csv = dir.resolve("a.csv");
        Files.writeString(csv, "1,1.00\n2,2.00\n");
        String ref = "csv:" + csv + "?idCol=0&valueCol=1";
        String first = new CsvConnector().load(ref).fingerprint();
        assertEquals(first, new CsvConnector().load(ref).fingerprint(), "同内容指纹必须可复现");

        Files.writeString(csv, "1,1.00\n2,9.99\n");   // 审批后换数据
        String tampered = new CsvConnector().load(ref).fingerprint();
        assertNotEquals(first, tampered, "内容变化必须改变指纹");
        assertThrows(Dataset.FingerprintMismatchException.class,
                () -> new CsvConnector().load(ref).requireFingerprint(first),
                "执行前复核必须拦截换数据");
    }

    @Test
    void JDBC接入与SELECT限制() throws Exception {
        String url = "jdbc:h2:mem:conn-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url);
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE orders(uid BIGINT, amount DECIMAL(12,2))");
            stmt.execute("INSERT INTO orders VALUES (7, 88.88), (8, 0.01)");
        }
        Dataset ds = new JdbcConnector().load(url + "#SELECT uid, amount FROM orders");
        assertArrayEquals(new long[]{7, 8}, sorted(ds.ids()));
        assertEquals(8888L, ds.amountCentsById().get(7L));

        assertThrows(IllegalArgumentException.class,
                () -> new JdbcConnector().load(url + "#DROP TABLE orders"),
                "非 SELECT 语句必须拒绝");
    }

    private static long[] sorted(long[] ids) {
        long[] copy = ids.clone();
        java.util.Arrays.sort(copy);
        return copy;
    }
}
