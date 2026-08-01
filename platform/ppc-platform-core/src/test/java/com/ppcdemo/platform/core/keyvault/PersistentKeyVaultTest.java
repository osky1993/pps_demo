package com.ppcdemo.platform.core.keyvault;

import com.ppcdemo.platform.core.phe.CipherAggregator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentKeyVaultTest {

    @TempDir
    Path dir;

    @Test
    void 重启后可恢复解密_销毁后不可() throws Exception {
        Path masterKey = dir.resolve("master.key");
        Path keyDir = dir.resolve("keys");
        long[] amounts = {12_50, 99_99, 3_000_00};

        // 第一个「进程」：建钥并密态聚合
        PersistentKeyVault vault1 = new PersistentKeyVault(keyDir, AesGcmEnvelope.fromFile(masterKey));
        byte[] pubkey = vault1.createTaskKey("t1", 2048);
        byte[] payload = CipherAggregator.aggregateForTransfer(pubkey, amounts);

        // 模拟节点重启：全新 vault 实例（仅共享磁盘与主密钥）
        PersistentKeyVault vault2 = new PersistentKeyVault(keyDir, AesGcmEnvelope.fromFile(masterKey));
        assertEquals(Arrays.stream(amounts).sum(), vault2.decrypt("t1", payload),
                "重启后应从盘上恢复密钥并正确解密");

        vault2.destroy("t1");
        assertFalse(Files.exists(keyDir.resolve("t1.key")), "销毁必须删除盘上材料");
        PersistentKeyVault vault3 = new PersistentKeyVault(keyDir, AesGcmEnvelope.fromFile(masterKey));
        assertThrows(IllegalStateException.class, () -> vault3.decrypt("t1", payload));
    }

    @Test
    void 盘上材料确为密文且主密钥不符拒绝() throws Exception {
        PersistentKeyVault vault = new PersistentKeyVault(dir.resolve("k"),
                AesGcmEnvelope.fromFile(dir.resolve("m1.key")));
        byte[] pubkey = vault.createTaskKey("t2", 2048);
        byte[] sealed = Files.readAllBytes(dir.resolve("k/t2.key"));

        // 模数字节不得以明文出现在盘上（信封确实生效）
        byte[] modulus = pubkey;
        assertFalse(contains(sealed, Arrays.copyOfRange(modulus, 1, 17)),
                "密钥材料不得明文落盘");

        // 换一把主密钥的节点无法解封
        PersistentKeyVault wrongMaster = new PersistentKeyVault(dir.resolve("k"),
                AesGcmEnvelope.fromFile(dir.resolve("m2.key")));
        assertThrows(IllegalStateException.class,
                () -> wrongMaster.decrypt("t2", CipherAggregator.aggregateForTransfer(pubkey, new long[]{1})));
    }

    @Test
    void 非法taskId拒绝防路径穿越() {
        PersistentKeyVault vault = new PersistentKeyVault(dir.resolve("k"),
                AesGcmEnvelope.fromFile(dir.resolve("m.key")));
        assertThrows(IllegalArgumentException.class, () -> vault.createTaskKey("../evil", 2048));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
