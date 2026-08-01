package com.ppcdemo.platform.gateway.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 请求签名（M5 §3.2，SDK 与 Gateway 共享的唯一实现，杜绝签名口径分叉）。
 *
 * 规范串 = appId\ntimestamp\nnonce\ncapability\nreqId\nSHA256(body)
 * 签名    = Base64(HMAC-SHA256(secret, 规范串))
 * 密钥经节点信封加密后入库（见 AppRegistry），DB 泄露不足以伪造签名。
 */
public final class HmacSigner {

    private HmacSigner() {
    }

    public static String canonical(String appId, long timestamp, String nonce,
                                   String capability, String reqId, String body) {
        return String.join("\n", appId, String.valueOf(timestamp), nonce,
                capability, reqId, sha256Hex(body == null ? "" : body));
    }

    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    /** 常量时间比较，防时序侧信道。 */
    public static boolean verify(String secret, String canonical, String presented) {
        String expected = sign(secret, canonical);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
