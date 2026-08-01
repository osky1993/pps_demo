package com.ppcdemo.platform.gateway;

import com.ppcdemo.platform.core.store.Database;
import com.ppcdemo.platform.gateway.catalog.Capability;
import com.ppcdemo.platform.gateway.catalog.CapabilityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-P3 门禁：能力目录变更需**双方审批**方生效（增删改是治理事件）。
 */
class CapabilityGovernanceTest {

    private CapabilityRegistry registry;

    @BeforeEach
    void setup() {
        Database db = Database.inMemory("cap-" + UUID.randomUUID());
        // 必需审批方：PPS 管理员 + 数据方
        registry = new CapabilityRegistry(db, Set.of("PPS-admin", "B-bureau"));
    }

    private Capability capability() {
        return new Capability("credit-check", "信用核验", Capability.Mode.SYNC_QUERY,
                "PIR_QUERY", "pai_cks", "B-bureau", "csv://B/credit",
                "INTERSECTION_DETAIL", 128, 100000, 2000, 4);
    }

    @Test
    void 单方审批不生效_双方审批后激活并对业务可见() {
        int v = registry.propose(capability(), "PPS-admin");
        assertEquals(1, v);
        assertEquals("PROPOSED", registry.state("credit-check", v).orElseThrow());
        assertTrue(registry.activeCatalog().find("credit-check").isEmpty(),
                "未审批前业务不可见");

        // 仅一方审批 → 仍不生效
        registry.approve("credit-check", v, "PPS-admin", "管理员-甲");
        assertEquals("PROPOSED", registry.state("credit-check", v).orElseThrow());
        assertTrue(registry.activeCatalog().find("credit-check").isEmpty());

        // 双方审批 → 激活、业务可见
        registry.approve("credit-check", v, "B-bureau", "合规官-乙");
        assertEquals("ACTIVE", registry.state("credit-check", v).orElseThrow());
        assertTrue(registry.activeCatalog().find("credit-check").isPresent(),
                "双方审批后业务可见");
    }

    @Test
    void 修改能力_新版本双批后替换旧版本() {
        int v1 = registry.propose(capability(), "PPS-admin");
        registry.approve("credit-check", v1, "PPS-admin", "甲");
        registry.approve("credit-check", v1, "B-bureau", "乙");
        assertEquals(2000, registry.activeCatalog().require("credit-check").perAppDailyQuota());

        // 提议改配额的新版本
        Capability changed = new Capability("credit-check", "信用核验", Capability.Mode.SYNC_QUERY,
                "PIR_QUERY", "pai_cks", "B-bureau", "csv://B/credit",
                "INTERSECTION_DETAIL", 128, 100000, 500, 4);   // perAppDaily 2000→500
        int v2 = registry.propose(changed, "PPS-admin");
        registry.approve("credit-check", v2, "PPS-admin", "甲");
        // 仅一方 → 旧版本仍生效
        assertEquals(2000, registry.activeCatalog().require("credit-check").perAppDailyQuota());
        registry.approve("credit-check", v2, "B-bureau", "乙");
        // 双批 → 新版本替换
        assertEquals(500, registry.activeCatalog().require("credit-check").perAppDailyQuota());
        assertEquals("RETIRED", registry.state("credit-check", v1).orElseThrow());
        assertEquals("ACTIVE", registry.state("credit-check", v2).orElseThrow());
    }
}
