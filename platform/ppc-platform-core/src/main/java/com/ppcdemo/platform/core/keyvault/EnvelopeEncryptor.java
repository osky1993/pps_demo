package com.ppcdemo.platform.core.keyvault;

/**
 * 密钥材料的信封加密（M2.5 #4）：KeyVault 持久化前 seal、加载时 unseal。
 * 实现：AesGcmEnvelope（本地主密钥）与 VaultTransitEnvelope（HashiCorp Vault Transit，
 * 即「加密即服务」——主密钥永不出 Vault）。
 */
public interface EnvelopeEncryptor {

    byte[] seal(byte[] plaintext);

    byte[] unseal(byte[] sealed);
}
