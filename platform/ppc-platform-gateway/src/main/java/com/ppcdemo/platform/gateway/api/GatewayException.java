package com.ppcdemo.platform.gateway.api;

/** 接入层业务异常，携带原因码（映射 HTTP 状态）。 */
public final class GatewayException extends RuntimeException {

    public enum Reason {
        UNAUTHENTICATED(401),
        FORBIDDEN(403),
        BAD_REQUEST(400),
        QUOTA_EXCEEDED(429),
        INTERNAL(500);

        public final int httpStatus;

        Reason(int httpStatus) {
            this.httpStatus = httpStatus;
        }
    }

    private final Reason reason;

    public GatewayException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
