package com.ppcdemo.platform.core.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 轻量 JDBC 门面：连接工厂 + 事务模板 + schema 初始化。
 * 一期 H2（嵌入）；生产换 PostgreSQL 只改 jdbcUrl，SQL 保持兼容子集。
 */
public final class Database {

    @FunctionalInterface
    public interface TxWork<T> {
        T apply(Connection conn) throws SQLException;
    }

    public static final class DataAccessException extends RuntimeException {
        public DataAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public Database(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /** 内存 H2（单测/单节点演示用；DB_CLOSE_DELAY 保持连接间可见）。 */
    public static Database inMemory(String name) {
        Database db = new Database("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        db.initSchema();
        return db;
    }

    /** 文件 H2（节点持久化）。 */
    public static Database file(String path) {
        Database db = new Database("jdbc:h2:file:" + path, "sa", "");
        db.initSchema();
        return db;
    }

    public void initSchema() {
        try (InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            inTransaction(conn -> {
                try (var stmt = conn.createStatement()) {
                    stmt.execute(ddl);
                }
                return null;
            });
        } catch (IOException e) {
            throw new UncheckedIOException("读取 schema.sql 失败", e);
        }
    }

    /** 事务模板：提交/回滚由模板负责，业务代码只写 SQL。 */
    public <T> T inTransaction(TxWork<T> work) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("数据库操作失败", e);
        }
    }
}
