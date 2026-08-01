package com.ppcdemo.dp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BudgetLedgerTest {

    @Test
    void 超预算熔断且不产生扣减() {
        BudgetLedger ledger = new BudgetLedger(2.0);
        ledger.consume("ds", 1.0);
        ledger.consume("ds", 1.0);
        assertThrows(BudgetLedger.BudgetExhaustedException.class, () -> ledger.consume("ds", 0.5));
        assertEquals(0.0, ledger.remaining("ds"), 1e-9, "失败的申请不得扣减预算");
    }

    @Test
    void 数据集之间预算独立() {
        BudgetLedger ledger = new BudgetLedger(1.0);
        ledger.consume("a", 1.0);
        ledger.consume("b", 0.5);
        assertEquals(0.5, ledger.remaining("b"), 1e-9);
    }
}
