package com.ppcdemo.platform.gateway.auth;

import com.ppcdemo.platform.gateway.api.PpsRequest;

/**
 * 请求认证（M5 §3.2，G2）：验签 + 重放窗口 + 应用状态。
 * 认证失败抛 AuthException（不泄露"应用不存在/密钥错误/已停用"的区别，防枚举）。
 */
public final class AppAuthenticator {

    public static final class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }

    /** 重放窗口：签名时刻与服务器时钟的最大偏差（含时钟漂移余量）。 */
    private final long clockSkewMillis;
    private final AppRegistry apps;

    public AppAuthenticator(AppRegistry apps, long clockSkewMillis) {
        this.apps = apps;
        this.clockSkewMillis = clockSkewMillis;
    }

    /** @param now 服务器当前时刻（注入以便测试确定性）；生产传 System.currentTimeMillis() */
    public void authenticate(PpsRequest req, long now) {
        if (Math.abs(now - req.timestamp()) > clockSkewMillis) {
            throw new AuthException("认证失败");   // 时间戳过期/超前（重放防护）
        }
        String canonical = HmacSigner.canonical(req.appId(), req.timestamp(), req.nonce(),
                req.capability(), req.reqId(), req.body());
        if (!apps.verifySignature(req.appId(), canonical, req.signature())) {
            throw new AuthException("认证失败");   // 签名不符 / 应用不存在 / 非 ACTIVE，一律同错
        }
    }
}
