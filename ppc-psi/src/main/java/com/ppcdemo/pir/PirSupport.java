package com.ppcdemo.pir;

import edu.alibaba.mpc4j.common.tool.EnvType;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.CpKsPirConfig;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.pai.PaiCpCksPirConfig;
import edu.alibaba.mpc4j.s2pc.pir.cppir.ks.simple.SimpleNaiveCpKsPirConfig;

/**
 * PIR 协议支撑（M4）：协议选型与编码约定。
 *
 * 选定 cppir/ks（客户端预处理关键字 PIR）族——**零 native 依赖，纯 Java**，
 * 与 M1 确立的 C5「默认纯 Java 路径」一脉相承（stdpir 系依赖 SEAL，M4 明确排除）。
 */
public final class PirSupport {

    /** value 定长要求（协议约束）：库内所有 value 统一长度，短补零、超长拒绝。 */
    public static final int DEFAULT_VALUE_BIT_LENGTH = 128;

    private PirSupport() {
    }

    /** @param protocol pai_cks（默认，论文实现完整）| simple_naive（对照组） */
    public static CpKsPirConfig configOf(String protocol) {
        CpKsPirConfig config = switch (protocol) {
            case "pai_cks" -> new PaiCpCksPirConfig.Builder().build();
            case "simple_naive" -> new SimpleNaiveCpKsPirConfig.Builder().build();
            default -> throw new IllegalArgumentException(
                    "未知 PIR 协议：" + protocol + "（支持 pai_cks | simple_naive）");
        };
        config.setEnvType(EnvType.STANDARD_JDK);
        return config;
    }

    /** 定长编码：短则右补零，超长即拒（协议要求库内 value 等长）。 */
    public static byte[] fitValue(byte[] raw, int byteLength) {
        if (raw.length > byteLength) {
            throw new IllegalArgumentException(
                    "value 超出契约声明长度 %d 字节：%d".formatted(byteLength, raw.length));
        }
        if (raw.length == byteLength) {
            return raw;
        }
        byte[] padded = new byte[byteLength];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        return padded;
    }

    /** 解码：去掉右侧补零（value 为文本时的还原约定）。 */
    public static String decodeText(byte[] value) {
        if (value == null) {
            return null;
        }
        int end = value.length;
        while (end > 0 && value[end - 1] == 0) {
            end--;
        }
        return new String(value, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }
}
