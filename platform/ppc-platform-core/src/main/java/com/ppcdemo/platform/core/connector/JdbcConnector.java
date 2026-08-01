package com.ppcdemo.platform.core.connector;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 接入：jdbc:<jdbcUrl>#<SQL>
 * SQL 结果集第 1 列 = ID（BIGINT），第 2 列（可选）= 金额（元，DECIMAL）×100 转分。
 * 流式游标读取（fetchSize），千万级不整表入内存的是 ResultSet 游标；
 * 当前实现聚合到内存数组（与引擎 job 文件格式对齐），超大表分页策略在运维手册约定。
 */
public final class JdbcConnector implements DatasetConnector {

    @Override
    public boolean supports(String ref) {
        return ref.startsWith("jdbc:") && ref.contains("#");
    }

    @Override
    public Dataset load(String ref) {
        int hash = ref.indexOf('#');
        String jdbcUrl = ref.substring(0, hash);
        String sql = ref.substring(hash + 1);
        if (!sql.regionMatches(true, 0, "SELECT", 0, 6)) {
            throw new IllegalArgumentException("数据接入 SQL 仅允许 SELECT：" + sql);
        }
        List<Long> ids = new ArrayList<>();
        Map<Long, Long> amounts = null;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(10_000);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                int columns = rs.getMetaData().getColumnCount();
                if (columns >= 2) {
                    amounts = new HashMap<>();
                }
                while (rs.next()) {
                    long id = rs.getLong(1);
                    ids.add(id);
                    if (amounts != null) {
                        BigDecimal yuan = rs.getBigDecimal(2);
                        amounts.put(id, yuan.movePointRight(2).longValueExact());
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC 数据接入失败：" + jdbcUrl, e);
        }
        long[] idArray = ids.stream().mapToLong(Long::longValue).toArray();
        return new Dataset(idArray, amounts, Fingerprints.of(idArray, amounts));
    }
}
