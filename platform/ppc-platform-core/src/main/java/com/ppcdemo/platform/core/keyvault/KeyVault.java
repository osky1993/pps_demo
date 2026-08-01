package com.ppcdemo.platform.core.keyvault;

/**
 * 任务级 Paillier 密钥保险箱（M2 §4.3）：私钥不出保险箱——解密在保险箱内完成，
 * 外界只拿得到公钥字节与解密结果。
 * 一期实现 EphemeralKeyVault（进程内，任务级生命周期）；生产换 Vault Transit 只换实现。
 * 任务中断即密钥消亡 → 任务失败重跑换新钥，这是「任务级密钥」语义的一部分而非缺陷。
 */
public interface KeyVault {

    /** 为任务创建密钥对，返回公钥序列化字节（模数）。已存在则幂等返回既有公钥。 */
    byte[] createTaskKey(String taskId, int keyBits);

    /** 保险箱内解密（payload 格式：[4字节 exponent][ciphertext 大整数]），返回明文 long。 */
    long decrypt(String taskId, byte[] cipherPayload);

    /** 任务结束销毁密钥（幂等）。 */
    void destroy(String taskId);
}
