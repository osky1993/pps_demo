package com.ppcdemo.pir;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-P1 门禁：匿踪查询正确性——命中返回正确 value、未命中不返回他人数据、批量查询顺序对齐。
 */
class LocalPirRunnerTest {

    private static final int VALUE_BITS = 128;
    private static final int VALUE_BYTES = VALUE_BITS / 8;

    private static Map<String, byte[]> buildDatabase(int n) {
        Map<String, byte[]> db = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            db.put("user-" + i, PirSupport.fitValue(
                    ("score:" + (600 + i % 300)).getBytes(StandardCharsets.UTF_8), VALUE_BYTES));
        }
        return db;
    }

    @ParameterizedTest
    @ValueSource(strings = {"pai_cks", "simple_naive"})
    void 命中查询返回正确value(String protocol) throws Exception {
        Map<String, byte[]> db = buildDatabase(1_000);
        List<String> queries = List.of("user-7", "user-500", "user-999");

        var result = LocalPirRunner.run(protocol, db, VALUE_BITS, queries);

        assertEquals(queries.size(), result.values().length);
        for (int i = 0; i < queries.size(); i++) {
            assertArrayEquals(db.get(queries.get(i)), result.values()[i],
                    protocol + " 查询 " + queries.get(i) + " 应返回库内对应 value");
        }
        System.out.printf("[%s] 1000 条库：init %d ms，查询 %d 条 %d ms，通信 %.1f KB%n",
                protocol, result.initMillis(), queries.size(), result.queryMillis(),
                (result.serverSentBytes() + result.clientSentBytes()) / 1024.0);
    }

    @Test
    void 未命中返回空且不泄露他人数据() throws Exception {
        Map<String, byte[]> db = buildDatabase(500);
        List<String> queries = new ArrayList<>(List.of("user-1", "不存在的键", "user-499"));

        var result = LocalPirRunner.run("pai_cks", db, VALUE_BITS, queries);

        assertArrayEquals(db.get("user-1"), result.values()[0]);
        byte[] miss = result.values()[1];
        if (miss != null) {
            // 未命中若返回占位值，必须不等于库中任何真实 value
            assertTrue(db.values().stream().noneMatch(v -> java.util.Arrays.equals(v, miss)),
                    "未命中不得返回库内他人数据");
        }
        assertArrayEquals(db.get("user-499"), result.values()[2]);
    }

    @Test
    void 定长编码与解码往返() {
        byte[] fitted = PirSupport.fitValue("信用良好".getBytes(StandardCharsets.UTF_8), VALUE_BYTES);
        assertEquals(VALUE_BYTES, fitted.length, "必须补齐到契约声明长度");
        assertEquals("信用良好", PirSupport.decodeText(fitted));
        assertThrows(IllegalArgumentException.class,
                () -> PirSupport.fitValue(new byte[VALUE_BYTES + 1], VALUE_BYTES),
                "超长 value 必须拒绝");
    }

    @Test
    void 批量查询顺序与请求一致() throws Exception {
        Map<String, byte[]> db = buildDatabase(200);
        List<String> queries = List.of("user-199", "user-0", "user-100", "user-50");

        var result = LocalPirRunner.run("pai_cks", db, VALUE_BITS, queries);

        for (int i = 0; i < queries.size(); i++) {
            assertNotNull(result.values()[i]);
            assertArrayEquals(db.get(queries.get(i)), result.values()[i],
                    "第 " + i + " 个结果必须对应第 " + i + " 个查询键");
        }
    }
}
