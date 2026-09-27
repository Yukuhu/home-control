package dev.andre.homecontrol.testsupport;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side TLS for fakes: a self-signed certificate no client trusts by default. Each common name gets one key and
 * certificate per test JVM, because an RSA key takes long enough to generate to show up in a suite's time.
 */
public final class TestTls {

    private static final Map<String, KeyManager[]> KEYS = new ConcurrentHashMap<>();

    private TestTls() {
    }

    /** A context whose server presents {@code CN=<commonName>}, valid from a day ago to a day ahead. */
    public static SSLContext serverContext(String commonName) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(KEYS.computeIfAbsent(commonName, TestTls::selfSigned), null, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not build a TLS context for " + commonName, e);
        }
    }

    private static KeyManager[] selfSigned(String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keys = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=" + commonName);
            Instant now = Instant.now();
            Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(subject, new BigInteger(64, new SecureRandom()),
                            Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(1))),
                            subject, keys.getPublic())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
            char[] password = "test".toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", keys.getPrivate(), password, new Certificate[]{certificate});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, password);
            return factory.getKeyManagers();
        } catch (GeneralSecurityException | OperatorCreationException | IOException e) {
            throw new IllegalStateException("Could not build a certificate for " + commonName, e);
        }
    }
}
