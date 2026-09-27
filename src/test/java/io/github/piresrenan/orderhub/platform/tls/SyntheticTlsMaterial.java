package io.github.piresrenan.orderhub.platform.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Generates synthetic, test-only TLS material with the JDK {@code keytool} and
 * performs production-shaped JSSE client requests against it.
 *
 * <p>Nothing produced here is a retained credential. Material is written only
 * to a caller-owned temporary directory and discarded with it. Clients use the
 * default PKIX trust manager and the standard {@code HTTPS} endpoint
 * identification algorithm; no trust-all or hostname bypass exists.</p>
 */
public final class SyntheticTlsMaterial {

    private static final String STORE_PASSWORD = "synthetic-test-store";

    private final Path directory;
    private final Path caCertificate;
    private final Path serverCertificate;
    private final Path serverPrivateKey;

    private SyntheticTlsMaterial(Path directory) {
        this.directory = directory;
        this.caCertificate = directory.resolve("ca.crt");
        this.serverCertificate = directory.resolve("tls.crt");
        this.serverPrivateKey = directory.resolve("tls.key");
    }

    /**
     * Creates a synthetic CA and a server certificate signed by it.
     *
     * @param directory empty temporary directory owned by the caller
     * @param caName distinguishing CA common name
     * @param dnsNames server certificate subject alternative DNS names
     * @return generated material
     * @throws Exception when keytool or key export fails
     */
    public static SyntheticTlsMaterial generate(
            Path directory,
            String caName,
            String... dnsNames) throws Exception {

        var material = new SyntheticTlsMaterial(directory);
        var caStore = directory.resolve("ca.p12").toString();
        var serverStore = directory.resolve("server.p12").toString();
        var request = directory.resolve("server.csr").toString();

        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=" + caName, "-ext", "bc:c", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", caStore,
                "-storepass", STORE_PASSWORD);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", caStore,
                "-storepass", STORE_PASSWORD,
                "-file", material.caCertificate.toString());

        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=" + dnsNames[0], "-validity", "2",
                "-storetype", "PKCS12", "-keystore", serverStore,
                "-storepass", STORE_PASSWORD);
        keytool("-certreq", "-alias", "server", "-keystore", serverStore,
                "-storepass", STORE_PASSWORD, "-file", request);

        var san = new StringBuilder("SAN=");
        for (var index = 0; index < dnsNames.length; index++) {
            san.append(index == 0 ? "" : ",").append("dns:").append(dnsNames[index]);
        }

        keytool("-gencert", "-rfc", "-alias", "ca", "-keystore", caStore,
                "-storepass", STORE_PASSWORD, "-infile", request,
                "-outfile", material.serverCertificate.toString(), "-validity", "2",
                "-ext", san.toString(),
                "-ext", "EKU=serverAuth",
                "-ext", "KU=digitalSignature,keyEncipherment");

        var store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(serverStore))) {
            store.load(input, STORE_PASSWORD.toCharArray());
        }
        var key = (PrivateKey) store.getKey("server", STORE_PASSWORD.toCharArray());
        Files.writeString(material.serverPrivateKey,
                pem("PRIVATE KEY", Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(key.getEncoded())));

        return material;
    }

    /**
     * Frames runtime-generated base64 DER content as PEM.
     *
     * <p>The label is a parameter so the repository guard rejecting committed
     * private-key PEM remains meaningful: source holds no key bytes and no
     * complete private-key marker; only temporary files ever contain keys.</p>
     *
     * @param label PEM label, for example {@code PRIVATE KEY}
     * @param base64Body base64 body
     * @return PEM text
     */
    public static String pem(String label, String base64Body) {
        return "-----BEGIN " + label + "-----\n" + base64Body + "\n-----END " + label + "-----\n";
    }

    /** @return PEM CA certificate path, the only trust input a client needs */
    public Path caCertificate() {
        return caCertificate;
    }

    /** @return PEM server certificate path */
    public Path serverCertificate() {
        return serverCertificate;
    }

    /** @return passwordless PKCS#8 PEM server private key path */
    public Path serverPrivateKey() {
        return serverPrivateKey;
    }

    /** @return directory containing this material */
    public Path directory() {
        return directory;
    }

    /**
     * Sends one HTTP/1.1 GET over TLS to a loopback port while validating the
     * server against {@code trustedCa} and {@code hostname}.
     *
     * <p>The TCP connection targets loopback, emulating Service DNS resolution,
     * while the TLS layer verifies the certificate chain with PKIX and the SAN
     * against {@code hostname} using the standard HTTPS identification rules.</p>
     *
     * @param trustedCa PEM CA the client trusts
     * @param hostname service identity the client expects
     * @param port loopback TLS port
     * @param path request path
     * @param headers additional raw header lines
     * @return raw HTTP response
     * @throws IOException including {@code SSLHandshakeException} on validation failure
     * @throws GeneralSecurityException when the client trust store cannot be built
     */
    public static String httpsGet(
            Path trustedCa,
            String hostname,
            int port,
            String path,
            String... headers) throws IOException, GeneralSecurityException {

        var trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        try (InputStream input = new ByteArrayInputStream(Files.readAllBytes(trustedCa))) {
            trustStore.setCertificateEntry("ca",
                    CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        var context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);

        var plain = new Socket();
        plain.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        plain.setSoTimeout(10_000);
        try (var socket = (SSLSocket) context.getSocketFactory()
                .createSocket(plain, hostname, port, true)) {
            var parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return exchange(socket, hostname, path, headers);
        }
    }

    /**
     * Sends one plaintext HTTP/1.1 GET to a loopback port.
     *
     * @param port loopback port
     * @param path request path
     * @return raw response, possibly empty when the peer closes the connection
     * @throws IOException when the connection fails
     */
    public static String plainHttpGet(int port, String path) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            return exchange(socket, "localhost", path);
        }
    }

    private static String exchange(
            Socket socket, String host, String path, String... headers) throws IOException {
        var request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Connection: close\r\n");
        for (var header : headers) {
            request.append(header).append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void keytool(String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + arguments[0] + "\n" + output);
        }
    }
}
