package com.ppcdemo.platform.core.audit;

import com.ppcdemo.platform.core.store.Database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 审计中心（M2 §4.5）：append-only 事件流 + 哈希链。
 * 每条事件 hash = SHA-256(prev_hash | task | party | type | payload)，篡改任意历史行即断链。
 */
public final class AuditCenter {

    public record AuditEvent(long seq, String taskId, String party, String eventType,
                             String payload, String prevHash, String hash) {
    }

    private static final String GENESIS = "0".repeat(64);

    private final Database db;

    public AuditCenter(Database db) {
        this.db = db;
    }

    public void append(String taskId, String party, String eventType, String payload) {
        db.inTransaction(conn -> {
            appendInTx(conn, taskId, party, eventType, payload);
            return null;
        });
    }

    /** 供出库网关在同一事务内记账 + 审计。链头查询带 FOR UPDATE 防并发交错。 */
    public void appendInTx(Connection conn, String taskId, String party,
                           String eventType, String payload) throws SQLException {
        String prevHash = GENESIS;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT hash FROM audit_event ORDER BY seq DESC LIMIT 1 FOR UPDATE")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    prevHash = rs.getString(1);
                }
            }
        }
        String hash = chainHash(prevHash, taskId, party, eventType, payload);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_event(task_id, party, event_type, payload, prev_hash, hash, created_at) " +
                "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, taskId);
            ps.setString(2, party);
            ps.setString(3, eventType);
            ps.setString(4, payload);
            ps.setString(5, prevHash);
            ps.setString(6, hash);
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public List<AuditEvent> byTask(String taskId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM audit_event WHERE task_id=? ORDER BY seq")) {
                ps.setString(1, taskId);
                return mapAll(ps);
            }
        });
    }

    /** 全链校验：任何一行被篡改（或删除中间行）都会返回 false。 */
    public boolean verifyChain() {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM audit_event ORDER BY seq")) {
                String expectedPrev = GENESIS;
                for (AuditEvent e : mapAll(ps)) {
                    if (!e.prevHash().equals(expectedPrev)
                            || !e.hash().equals(chainHash(e.prevHash(), e.taskId(), e.party(),
                                    e.eventType(), e.payload()))) {
                        return false;
                    }
                    expectedPrev = e.hash();
                }
                return true;
            }
        });
    }

    private static List<AuditEvent> mapAll(PreparedStatement ps) throws SQLException {
        List<AuditEvent> events = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                events.add(new AuditEvent(rs.getLong("seq"), rs.getString("task_id"),
                        rs.getString("party"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getString("prev_hash"), rs.getString("hash")));
            }
        }
        return events;
    }

    private static String chainHash(String prevHash, String taskId, String party,
                                    String eventType, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = String.join("|", prevHash, String.valueOf(taskId), party,
                    eventType, String.valueOf(payload));
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
