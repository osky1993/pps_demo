package com.ppcdemo.platform.gateway.api;

/**
 * 业务系统的签名请求（M5 §3.2）。SDK 构造并签名，Gateway 验签。
 *
 * @param appId      应用身份
 * @param capability 能力 id（业务唯一可见的调用单元）
 * @param reqId      幂等键（业务系统生成，全局唯一，建议 = 业务单据号）
 * @param timestamp  签名时刻（epoch millis，用于重放窗口校验）
 * @param nonce      随机数（同窗口内防重放）
 * @param body       业务参数（能力的 inputSchema 约定，如查询 key）
 * @param signature  Base64(HMAC-SHA256(secret, 规范串))
 */
public record PpsRequest(
        String appId,
        String capability,
        String reqId,
        long timestamp,
        String nonce,
        String body,
        String signature) {
}
