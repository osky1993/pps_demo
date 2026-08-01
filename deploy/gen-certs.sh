#!/usr/bin/env bash
# 双节点 mTLS 证书生成（证书指纹式互信，与 MtlsChannelTest 同构）。
# 用法：./gen-certs.sh <输出目录> [有效期天数]
# 产物：nodeA.p12 / nodeB.p12（身份）、nodeA-trust.p12 / nodeB-trust.p12（只信任对方）。
# 生成后将各节点的 .p12 与 -trust.p12 分发至对应机器，口令经安全渠道传递并替换默认值。
set -euo pipefail

OUT="${1:?用法: ./gen-certs.sh <输出目录> [有效期天数]}"
DAYS="${2:-365}"
PASS="${PPC_CERT_PASS:-changeit}"
mkdir -p "$OUT"

for NODE in nodeA nodeB; do
  keytool -genkeypair -alias "$NODE" -keyalg RSA -keysize 3072 \
    -storetype PKCS12 -keystore "$OUT/$NODE.p12" -storepass "$PASS" \
    -dname "CN=$NODE,O=ppc-demo" \
    -ext "SAN=dns:localhost,dns:$NODE,ip:127.0.0.1" -validity "$DAYS"
  keytool -exportcert -alias "$NODE" -keystore "$OUT/$NODE.p12" \
    -storepass "$PASS" -file "$OUT/$NODE.cer"
done

keytool -importcert -noprompt -alias nodeB -storetype PKCS12 \
  -keystore "$OUT/nodeA-trust.p12" -storepass "$PASS" -file "$OUT/nodeB.cer"
keytool -importcert -noprompt -alias nodeA -storetype PKCS12 \
  -keystore "$OUT/nodeB-trust.p12" -storepass "$PASS" -file "$OUT/nodeA.cer"

echo "完成：$OUT 下 nodeA/nodeB 身份库与互信库。生产环境请更换口令并妥善分发。"
