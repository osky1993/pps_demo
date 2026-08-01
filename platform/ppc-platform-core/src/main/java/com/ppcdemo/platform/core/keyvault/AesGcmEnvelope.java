package com.ppcdemo.platform.core.keyvault;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 本地主密钥信封（AES-256-GCM）：主密钥来自受权限保护的文件（32 字节）。
 * 适用单机/开发部署；生产建议 VaultTransitEnvelope（主密钥不落节点）。
 * 密文格式：[12B IV][GCM 密文+tag]。
 */
public final class AesGcmEnvelope implements EnvelopeEncryptor {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec masterKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmEnvelope(byte[] masterKey32) {
        if (masterKey32.length != 32) {
            throw new IllegalArgumentException("主密钥必须 32 字节，实际 " + masterKey32.length);
        }
        this.masterKey = new SecretKeySpec(masterKey32, "AES");
    }

    /** 从主密钥文件加载（不存在则生成并以 600 权限落盘）。 */
    public static AesGcmEnvelope fromFile(Path masterKeyFile) {
        try {
            if (Files.notExists(masterKeyFile)) {
                byte[] fresh = new byte[32];
                new SecureRandom().nextBytes(fresh);
                if (masterKeyFile.getParent() != null) {
                    Files.createDirectories(masterKeyFile.getParent());
                }
                Files.write(masterKeyFile, fresh);
                try {
                    Files.setPosixFilePermissions(masterKeyFile,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException nonPosix) {
                    // Windows 等非 POSIX 文件系统跳过
                }
            }
            return new AesGcmEnvelope(Files.readAllBytes(masterKeyFile));
        } catch (IOException e) {
            throw new UncheckedIOException("主密钥文件读写失败：" + masterKeyFile, e);
        }
    }

    @Override
    public byte[] seal(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext);
            byte[] sealed = new byte[IV_BYTES + cipherText.length];
            System.arraycopy(iv, 0, sealed, 0, IV_BYTES);
            System.arraycopy(cipherText, 0, sealed, IV_BYTES, cipherText.length);
            return sealed;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("信封加密失败", e);
        }
    }

    @Override
    public byte[] unseal(byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 0, IV_BYTES)));
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("信封解密失败（主密钥不符或密文被篡改）", e);
        }
    }
}
