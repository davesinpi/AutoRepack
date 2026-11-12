package com.autorepack;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * The signing key for repackaged copies. It's generated once on this phone inside the
 * Android Keystore (it never leaves the device), and reused so copies can be updated in place.
 */
final class LocalSigningKey {
    private static final String ALIAS = "autorepack-signing-key";

    final PrivateKey privateKey;
    final X509Certificate certificate;

    private LocalSigningKey(PrivateKey key, X509Certificate cert) {
        this.privateKey = key;
        this.certificate = cert;
    }

    static synchronized LocalSigningKey get() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            long now = System.currentTimeMillis();
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            gen.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=AutoRepack local key, O=AutoRepack"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(new Date(now - 24L * 3600 * 1000))
                    .setCertificateNotAfter(new Date(now + 30L * 365 * 24 * 3600 * 1000))
                    .build());
            gen.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        if (key == null || cert == null) throw new IllegalStateException("Signing key is unavailable");
        return new LocalSigningKey(key, cert);
    }
}
