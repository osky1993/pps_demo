package com.ppcdemo.platform.gateway.catalog;

import com.ppcdemo.platform.core.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 能力目录变更治理（M5-P3 §4.3 / §5.P3）：能力的增删改**需全部必需方审批**方生效。
 *
 * 业务系统只见 ACTIVE 版本（activeCatalog）。变更走「提议→双方审批→自动激活」，
 * 复用既有例外审批的「全员批准」语义——能力目录即数据使用清单，其变更本身是治理事件。
 */
public final class CapabilityRegistry {

    private final Database db;
    private final Set<String> requiredApprovers;   // 必需审批方（如 PPS 管理员 + 数据方）

    public CapabilityRegistry(Database db, Set<String> requiredApprovers) {
        this.db = db;
        this.requiredApprovers = Set.copyOf(requiredApprovers);
    }

    /** 提议新版本（新增或修改）。返回版本号。 */
    public int propose(Capability capability, String proposer) {
        return db.inTransaction(conn -> {
            int nextVersion = 1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(version),0)+1 FROM pps_capability WHERE capability_id=?")) {
                ps.setString(1, capability.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    nextVersion = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO pps_capability(capability_id, version, spec, state, proposer, created_at) " +
                    "VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, capability.id());
                ps.setInt(2, nextVersion);
                ps.setString(3, CapabilitySerde.serialize(capability));
                ps.setString(4, "PROPOSED");
                ps.setString(5, proposer);
                ps.setTimestamp(6, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            return nextVersion;
        });
    }

    /** 某方审批某版本；全部必需方批准后自动激活（并退休同能力旧的 ACTIVE 版本）。 */
    public void approve(String capabilityId, int version, String party, String approver) {
        db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO pps_capability_approval(capability_id, version, party, approver, approved_at) " +
                    "KEY(capability_id, version, party) VALUES (?,?,?,?,?)")) {
                ps.setString(1, capabilityId);
                ps.setInt(2, version);
                ps.setString(3, party);
                ps.setString(4, approver);
                ps.setTimestamp(5, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            // 收集已批准方
            Set<String> approved = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT party FROM pps_capability_approval WHERE capability_id=? AND version=?")) {
                ps.setString(1, capabilityId);
                ps.setInt(2, version);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        approved.add(rs.getString("party"));
                    }
                }
            }
            if (approved.containsAll(requiredApprovers)) {
                // 退休旧 ACTIVE、激活本版本
                try (PreparedStatement retire = conn.prepareStatement(
                        "UPDATE pps_capability SET state='RETIRED' " +
                        "WHERE capability_id=? AND state='ACTIVE'")) {
                    retire.setString(1, capabilityId);
                    retire.executeUpdate();
                }
                try (PreparedStatement activate = conn.prepareStatement(
                        "UPDATE pps_capability SET state='ACTIVE' WHERE capability_id=? AND version=?")) {
                    activate.setString(1, capabilityId);
                    activate.setInt(2, version);
                    activate.executeUpdate();
                }
            }
            return null;
        });
    }

    /** 业务系统可见的当前目录（仅 ACTIVE 版本）。 */
    public CapabilityCatalog activeCatalog() {
        List<Capability> active = db.inTransaction(conn -> {
            List<Capability> list = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT spec FROM pps_capability WHERE state='ACTIVE'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(CapabilitySerde.deserialize(rs.getString("spec")));
                    }
                }
            }
            return list;
        });
        return CapabilityCatalog.of(active);
    }

    public Optional<String> state(String capabilityId, int version) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT state FROM pps_capability WHERE capability_id=? AND version=?")) {
                ps.setString(1, capabilityId);
                ps.setInt(2, version);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString("state")) : Optional.<String>empty();
                }
            }
        });
    }
}
