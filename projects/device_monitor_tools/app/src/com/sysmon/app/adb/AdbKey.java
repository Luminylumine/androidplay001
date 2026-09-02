package com.sysmon.app.adb;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.sysmon.app.SysLog;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Calendar;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

import java.net.Socket;
import java.security.Principal;

/**
 * Per-install ADB host identity.
 *
 * The private key is generated and retained by Android Keystore. It is never
 * embedded in the APK and cannot be exported through the app process.
 */
public final class AdbKey {

    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "sysmon-adb-host";

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final RSAPublicKey publicKey;
    private volatile SSLContext sslContext;

    public AdbKey() {
        PrivateKey pk = null;
        X509Certificate cert = null;
        RSAPublicKey pub = null;
        try {
            KeyStore ks = KeyStore.getInstance(STORE);
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                Calendar from = Calendar.getInstance();
                Calendar until = Calendar.getInstance();
                until.add(Calendar.YEAR, 20);
                KeyPairGeneratorHolder.generate(from.getTime(), until.getTime());
            }
            pk = (PrivateKey) ks.getKey(ALIAS, null);
            cert = (X509Certificate) ks.getCertificate(ALIAS);
            if (cert != null && cert.getPublicKey() instanceof RSAPublicKey) {
                pub = (RSAPublicKey) cert.getPublicKey();
            }
            SysLog.i("AdbKey loaded from Android Keystore, modulusBits="
                    + (pub == null ? 0 : pub.getModulus().bitLength()));
        } catch (Exception e) {
            SysLog.e("AdbKey Keystore load failed: " + e);
        }
        privateKey = pk;
        certificate = cert;
        publicKey = pub;
    }

    public boolean available() {
        return privateKey != null && publicKey != null && certificate != null;
    }

    /** ADB public-key wire format: base64(android_pubkey struct) + comment + NUL. */
    public byte[] adbPublicKey() {
        if (!available()) throw new IllegalStateException("ADB key unavailable");
        try {
            BigInteger modulus = publicKey.getModulus();
            BigInteger exponent = publicKey.getPublicExponent();
            final int words = 2048 / 8 / 4;
            final int modulusSize = 2048 / 8;
            final int publicKeySize = 524;

            BigInteger r32 = BigInteger.ONE.shiftLeft(32);
            BigInteger n0inv = modulus.remainder(r32).modInverse(r32).negate();
            BigInteger r = BigInteger.ONE.shiftLeft(modulusSize * 8);
            BigInteger rr = r.modPow(BigInteger.valueOf(2), modulus);

            ByteBuffer buf = ByteBuffer.allocate(publicKeySize).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(words);
            buf.putInt(n0inv.intValue());
            for (int w : toAdbEncoded(modulus, words)) buf.putInt(w);
            for (int w : toAdbEncoded(rr, words)) buf.putInt(w);
            buf.putInt(exponent.intValue());

            byte[] base64 = Base64.encode(buf.array(), Base64.NO_WRAP);
            byte[] comment = " sysmon\u0000".getBytes("UTF-8");
            byte[] out = new byte[base64.length + comment.length];
            System.arraycopy(base64, 0, out, 0, base64.length);
            System.arraycopy(comment, 0, out, base64.length, comment.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("adbPublicKey", e);
        }
    }

    /** Legacy ADB AUTH signature. The private operation is performed in Keystore. */
    public byte[] sign(byte[] data) {
        if (!available()) throw new IllegalStateException("ADB key unavailable");
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("ADB sign failed", e);
        }
    }

    public SSLContext sslContext() {
        SSLContext c = sslContext;
        if (c == null) {
            synchronized (this) {
                c = sslContext;
                if (c == null) {
                    c = buildSslContext();
                    sslContext = c;
                }
            }
        }
        return c;
    }

    private SSLContext buildSslContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new javax.net.ssl.KeyManager[]{keyManager()},
                    new javax.net.ssl.TrustManager[]{trustManager()}, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("sslContext", e);
        }
    }

    private X509ExtendedKeyManager keyManager() {
        return new X509ExtendedKeyManager() {
            private final String alias = "key";

            @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                for (String type : keyTypes) if ("RSA".equals(type)) return alias;
                return null;
            }
            @Override public X509Certificate[] getCertificateChain(String requested) {
                return alias.equals(requested) ? new X509Certificate[]{certificate} : null;
            }
            @Override public PrivateKey getPrivateKey(String requested) {
                return alias.equals(requested) ? privateKey : null;
            }
            @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
            @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
        };
    }

    /** ADB authenticates the client identity; its local server certificate is not a public CA cert. */
    private X509ExtendedTrustManager trustManager() {
        return new X509ExtendedTrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    private static int[] toAdbEncoded(BigInteger value, int words) {
        int[] out = new int[words];
        BigInteger base = BigInteger.ONE.shiftLeft(32);
        BigInteger tmp = value;
        for (int i = 0; i < words; i++) {
            BigInteger[] qr = tmp.divideAndRemainder(base);
            tmp = qr[0];
            out[i] = qr[1].intValue();
        }
        return out;
    }

    private static final class KeyPairGeneratorHolder {
        static void generate(java.util.Date from, java.util.Date until) throws Exception {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, STORE);
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new javax.security.auth.x500.X500Principal("CN=SysMon"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(from)
                    .setCertificateNotAfter(until)
                    .build());
            generator.generateKeyPair();
        }
    }
}
