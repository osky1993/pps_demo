package com.ppcdemo.platform.gateway.auth;

import com.ppcdemo.platform.core.keyvault.EnvelopeEncryptor;
import com.ppcdemo.platform.core.store.Database;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 接入应用注册表（pps_application）。
 *
 * HMAC 验签是对称的——gateway 必须持有密钥。为使 DB 泄露不足以伪造签名，
 * 密钥经节点信封加密（M2.5 #4 EnvelopeEncryptor）后入库：攻击者还需节点主密钥才能解封。
 * 未密封的密钥只在 verifySignature 内部短暂存在，不外泄。
 */
public final class AppRegistry {

    public enum AppState { ACTIVE, SUSPENDED }

    public record App(String appId, String appName, AppState state) {
    }

    private final Database db;
    private final EnvelopeEncryptor envelope;

    public AppRegistry(Database db, EnvelopeEncryptor envelope) {
        this.db = db;
        this.envelope = envelope;
    }

    /** 注册应用；secret 密封后入库，方法返回后调用方应丢弃明文。 */
    public void register(String appId, String appName, String secret) {
        String sealed = Base64.getEncoder().encodeToString(
                envelope.seal(secret.getBytes(StandardCharsets.UTF_8)));
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO pps_application(app_id, app_name, secret_sealed, state, created_at) " +
                    "KEY(app_id) VALUES (?,?,?,?,?)")) {
                ps.setString(1, appId);
                ps.setString(2, appName);
                ps.setString(3, sealed);
                ps.setString(4, AppState.ACTIVE.name());
                ps.setTimestamp(5, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void grant(String appId, String capabilityId, Integer dailyLimit) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO pps_app_grant(app_id, capability_id, daily_limit) " +
                    "KEY(app_id, capability_id) VALUES (?,?,?)")) {
                ps.setString(1, appId);
                ps.setString(2, capabilityId);
                ps.setObject(3, dailyLimit);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setState(String appId, AppState state) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE pps_application SET state=? WHERE app_id=?")) {
                ps.setString(1, state.name());
                ps.setString(2, appId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public Optional<App> find(String appId) {
        return db.inTransaction(conn -> selectApp(conn, appId)
                .map(row -> new App(row.appId(), row.appName(), row.state())));
    }

    public boolean hasGrant(String appId, String capabilityId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM pps_app_grant WHERE app_id=? AND capability_id=?")) {
                ps.setString(1, appId);
                ps.setString(2, capabilityId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public Integer dailyLimit(String appId, String capabilityId) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT daily_limit FROM pps_app_grant WHERE app_id=? AND capability_id=?")) {
                ps.setString(1, appId);
                ps.setString(2, capabilityId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? (Integer) rs.getObject("daily_limit") : null;
                }
            }
        });
    }

    /** 验签：内部解封密钥、常量时间比较，密钥不外泄。应用不存在或非 ACTIVE 直接返回 false。 */
    public boolean verifySignature(String appId, String canonical, String presented) {
        return db.inTransaction(conn -> selectApp(conn, appId)
                .filter(row -> row.state() == AppState.ACTIVE)
                .map(row -> {
                    byte[] secret = envelope.unseal(Base64.getDecoder().decode(row.sealed()));
                    return HmacSigner.verify(new String(secret, StandardCharsets.UTF_8),
                            canonical, presented);
                })
                .orElse(false));
    }

    private record Row(String appId, String appName, String sealed, AppState state) {
    }

    private Optional<Row> selectApp(java.sql.Connection conn, String appId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pps_application WHERE app_id=?")) {
            ps.setString(1, appId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Row(rs.getString("app_id"), rs.getString("app_name"),
                        rs.getString("secret_sealed"), AppState.valueOf(rs.getString("state"))));
            }
        }
    }
}
