package com.ppcdemo.platform.core.phe;

import com.ppcdemo.platform.core.keyvault.EphemeralKeyVault;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CipherAggregatorTest {

    @Test
    void 密态聚合与保险箱解密互通() {
        EphemeralKeyVault vault = new EphemeralKeyVault();
        byte[] pubkey = vault.createTaskKey("t1", 2048);

        long[] amounts = {12_50, 99_99, 3_000_00, 1, 700_000_000L};
        byte[] payload = CipherAggregator.aggregateForTransfer(pubkey, amounts);

        assertEquals(Arrays.stream(amounts).sum(), vault.decrypt("t1", payload),
                "密态聚合解密必须与明文求和一致");
    }

    @Test
    void 大额与指数对齐正确性() {
        // 覆盖 javallier 变长指数场景：16 的幂次值编码指数非 0，聚合器需对齐到 0
        EphemeralKeyVault vault = new EphemeralKeyVault();
        byte[] pubkey = vault.createTaskKey("t2", 2048);
        long[] amounts = {16, 256, 65_536, 16_777_216, 1};
        byte[] payload = CipherAggregator.aggregateForTransfer(pubkey, amounts);
        assertEquals(Arrays.stream(amounts).sum(), vault.decrypt("t2", payload));
    }

    @Test
    void 密钥销毁后拒绝解密() {
        EphemeralKeyVault vault = new EphemeralKeyVault();
        byte[] pubkey = vault.createTaskKey("t3", 2048);
        byte[] payload = CipherAggregator.aggregateForTransfer(pubkey, new long[]{42});
        vault.destroy("t3");
        assertThrows(IllegalStateException.class, () -> vault.decrypt("t3", payload));
    }
}
