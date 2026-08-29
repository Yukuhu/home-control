package dev.andre.shield.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/** A self-signed RSA identity. Once paired, this certificate IS the credential. */
public record ClientCertificate(KeyPair keyPair, X509Certificate certificate) {

    private static final Duration VALIDITY = Duration.ofDays(365 * 20);

    public static ClientCertificate generate(String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=" + commonName);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .build(keyPair.getPrivate());

            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(
                            subject,
                            new BigInteger(64, new SecureRandom()),
                            Date.from(now),
                            Date.from(now.plus(VALIDITY)),
                            subject,
                            keyPair.getPublic()
                    ).build(signer));

            return new ClientCertificate(keyPair, certificate);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate client certificate", e);
        }
    }

    /** SHA-256 of the encoded certificate as uppercase hex; how a device is pinned. */
    public static String fingerprintOf(X509Certificate certificate) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint the certificate", e);
        }
    }
}
