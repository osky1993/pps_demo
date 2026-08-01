package com.ppcdemo.platform.core.net;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * mTLS 上下文构建（M2 §4.7）：本方 PKCS12 身份库 + 只信任对方证书的信任库（证书指纹式互信）。
 * 联盟 CA 模式在 deploy/gen-certs.sh 中脚本化；本类不关心证书如何签发。
 */
public final class TlsContexts {

    private TlsContexts() {
    }

    public static SSLContext build(Path keyStoreP12, char[] storePassword, Path trustStoreP12) {
        try {
            KeyStore identity = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keyStoreP12)) {
                identity.load(in, storePassword);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(identity, storePassword);

            KeyStore trust = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(trustStoreP12)) {
                trust.load(in, storePassword);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);

            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("构建 TLS 上下文失败：" + keyStoreP12, e);
        }
    }
}
