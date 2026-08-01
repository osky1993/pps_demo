package com.ppcdemo.psi;

import edu.alibaba.mpc4j.common.rpc.desc.SecurityModel;
import edu.alibaba.mpc4j.common.tool.EnvType;
import edu.alibaba.mpc4j.s2pc.pso.psi.PsiConfig;
import edu.alibaba.mpc4j.s2pc.pso.psi.cuckoo.kkrt16.Kkrt16PsiConfig;
import edu.alibaba.mpc4j.s2pc.pso.psi.mpoprf.rr22.Rr22PsiConfig;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PSI 运行器公共支撑：协议配置构造与元素编解码。
 * 被 Local（内存 RPC）与 Netty（双机）两种运行器复用，协议语义保持一致。
 */
public final class PsiSupport {

    /** ID 统一编码为 16 字节 block（与 mpc4j 测试用例的元素长度一致）。 */
    public static final int ELEMENT_BYTE_LENGTH = 16;

    /** RR22 底层 WYKW21 VOLE 单批上限 2^22，经 OKVS 膨胀（约1.35×）反推的安全输入上限。 */
    public static final int RR22_SINGLE_BATCH_MAX = 3_000_000;

    private PsiSupport() {
    }

    /** @param protocol rr22 | kkrt16（poc.yaml 的 psi.protocol 语义） */
    public static PsiConfig configOf(String protocol) {
        PsiConfig config = switch (protocol) {
            case "rr22" -> new Rr22PsiConfig.Builder(SecurityModel.SEMI_HONEST).build();
            case "kkrt16" -> new Kkrt16PsiConfig.Builder().build();
            default -> throw new IllegalArgumentException("未知 PSI 协议：" + protocol + "（支持 rr22 | kkrt16）");
        };
        // 默认 EnvType.STANDARD 走 mpc4j-native-tool（C++，需另行 cmake 构建），
        // 平台默认纯 Java 路径（M2 约束 C5）；native 加速是性能优化开关而非功能依赖。
        config.setEnvType(EnvType.STANDARD_JDK);
        return config;
    }

    /** 按规模自动选型（M2 §4.2）：≤300万 RR22，超出用 KKRT16。可被契约显式覆盖。 */
    public static String autoProtocol(int maxPartySize) {
        return maxPartySize <= RR22_SINGLE_BATCH_MAX ? "rr22" : "kkrt16";
    }

    public static Set<ByteBuffer> encode(long[] ids) {
        return Arrays.stream(ids)
                .mapToObj(id -> ByteBuffer.wrap(
                        ByteBuffer.allocate(ELEMENT_BYTE_LENGTH).putLong(0, id).array()))
                .collect(Collectors.toSet());
    }

    public static Set<Long> decode(Set<ByteBuffer> elements) {
        return elements.stream()
                .map(buffer -> buffer.getLong(0))
                .collect(Collectors.toSet());
    }
}
