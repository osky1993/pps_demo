package com.ppcdemo.platform.core.psi;

import java.nio.file.Path;

/**
 * 协议面 TLS 配置（部署级开关，M2.5）。**默认不启用**——不配置本档案即走原明文路径
 * （适用于专线/VPN 兜底的部署）；配置后 psi-engine 以 mTLS 隧道包裹协议流。
 *
 * @param keyStore     本方身份库（PKCS12，与控制面证书体系同源，可复用 deploy/gen-certs.sh 产物）
 * @param trustStore   只含对方证书的信任库
 * @param storePass    库口令
 * @param tlsListenPort 本方公网 TLS 监听端口（对方 tlsPeer 指向它）
 * @param tlsPeer      对方公网 TLS 端点 host:port
 */
public record ProtocolTlsProfile(Path keyStore, Path trustStore, String storePass,
                                 int tlsListenPort, String tlsPeer) {
}
