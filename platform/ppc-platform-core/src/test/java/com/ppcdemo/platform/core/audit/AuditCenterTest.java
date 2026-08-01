package com.ppcdemo.platform.core.audit;

import com.ppcdemo.platform.core.store.Database;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditCenterTest {

    @Test
    void 哈希链完整且篡改可检() {
        Database db = Database.inMemory("audit-" + UUID.randomUUID());
        AuditCenter audit = new AuditCenter(db);
        audit.append("t1", "A", "TASK_CREATED", "{}");
        audit.append("t1", "A", "RUNNING", null);
        audit.append("t2", "B", "TASK_CREATED", "{}");

        assertTrue(audit.verifyChain(), "未篡改的链必须校验通过");
        assertEquals(2, audit.byTask("t1").size());

        // 模拟篡改：直接改历史事件的 payload（现实中=DBA 越权改库）
        db.inTransaction(conn -> {
            try (var ps = conn.prepareStatement("UPDATE audit_event SET payload='{tampered}' WHERE seq=1")) {
                ps.executeUpdate();
            }
            return null;
        });
        assertFalse(audit.verifyChain(), "篡改历史事件必须导致链校验失败");
    }
}
