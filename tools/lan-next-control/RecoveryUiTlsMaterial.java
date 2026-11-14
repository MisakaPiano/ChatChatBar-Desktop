import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Base64;

/** CI-only standard JDK exporter. No system trust store or registry mutation. */
public final class RecoveryUiTlsMaterial {
    private static void pem(Path path, String kind, byte[] bytes) throws Exception {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes);
        Files.writeString(path, "-----BEGIN " + kind + "-----\n" + body + "\n-----END " + kind + "-----\n",
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        char[] password = System.getenv("CCB_UI_TLS_STOREPASS").toCharArray();
        byte[] encoded = null;
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(root.resolve("server.p12"))) { keys.load(input, password); }
            Key key = keys.getKey("server", password);
            if (!(key instanceof PrivateKey) || !"PKCS#8".equals(key.getFormat())) throw new Exception("KEY_FORMAT");
            Certificate certificate = keys.getCertificate("server");
            encoded = key.getEncoded(); pem(root.resolve("server-key.pem"), "PRIVATE KEY", encoded);
            pem(root.resolve("server-cert.pem"), "CERTIFICATE", certificate.getEncoded());
            KeyStore trust = KeyStore.getInstance("JKS"); trust.load(null, new char[0]);
            trust.setCertificateEntry("synthetic-loopback-only", certificate);
            // Public certificates only; no secret password is passed on the test JVM command line.
            try (var out = Files.newOutputStream(root.resolve("test-trust.jks"), StandardOpenOption.CREATE_NEW)) {
                trust.store(out, new char[0]);
            }
        } finally { Arrays.fill(password, '\0'); if (encoded != null) Arrays.fill(encoded, (byte) 0); }
    }
}
