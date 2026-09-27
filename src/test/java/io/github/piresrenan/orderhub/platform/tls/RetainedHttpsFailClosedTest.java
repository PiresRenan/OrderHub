package io.github.piresrenan.orderhub.platform.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.github.piresrenan.orderhub.OrderHubApplication;

/**
 * Proves the retained profile fails closed rather than downgrading to HTTP
 * when deployment-owned TLS material is absent or unusable.
 *
 * <p>These contexts fail while creating the web server, before any datasource
 * connection is attempted, so no database fixture is required.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class RetainedHttpsFailClosedTest {

    private static final String KEY_CANARY =
            "ORDERHUB-SYNTHETIC-PRIVATE-KEY-CANARY-7f3c";

    @TempDir
    private Path directory;

    /**
     * Why: a retained replica without its Secret must not serve HTTP.
     * Covers: the retained profile with its committed default mount paths,
     * which do not exist outside the container.
     * Prevents: silent plaintext fallback when the Secret mount is missing.
     */
    @Test
    void refusesStartupWhenRetainedMaterialIsAbsent() {
        assertThatThrownBy(() -> start(Map.of()))
                .isNotInstanceOf(AssertionError.class)
                .hasStackTraceContaining("tls.crt");
    }

    /**
     * Why: certificate and key are both mandatory.
     * Covers: a present certificate with a missing private key.
     * Prevents: partial material yielding an HTTP or unauthenticated listener.
     */
    @Test
    void refusesStartupWhenPrivateKeyIsMissing() throws Exception {
        var material = SyntheticTlsMaterial.generate(
                directory, "Fail Closed CA", RetainedHttpsTransportTest.SERVICE_IDENTITY);

        assertThatThrownBy(() -> start(Map.of(
                "spring.ssl.bundle.pem.orderhub-server.keystore.certificate",
                material.serverCertificate().toUri().toString(),
                "spring.ssl.bundle.pem.orderhub-server.keystore.private-key",
                directory.resolve("absent.key").toUri().toString())))
                .isNotInstanceOf(AssertionError.class)
                .hasStackTraceContaining("absent.key");
    }

    /**
     * Why: startup diagnostics must not disclose key material.
     * Covers: an unreadable private-key file seeded with a recognizable canary.
     * Prevents: private-key bytes reaching logs or exception output.
     */
    @Test
    void refusesMalformedKeyWithoutLeakingIt(CapturedOutput output) throws Exception {
        var material = SyntheticTlsMaterial.generate(
                directory, "Canary CA", RetainedHttpsTransportTest.SERVICE_IDENTITY);
        var key = Files.writeString(directory.resolve("canary.key"),
                SyntheticTlsMaterial.pem("PRIVATE KEY", KEY_CANARY));

        assertThatThrownBy(() -> start(Map.of(
                "spring.ssl.bundle.pem.orderhub-server.keystore.certificate",
                material.serverCertificate().toUri().toString(),
                "spring.ssl.bundle.pem.orderhub-server.keystore.private-key",
                key.toUri().toString())))
                .isNotInstanceOf(AssertionError.class)
                .satisfies(failure -> assertThat(stackTrace(failure)).doesNotContain(KEY_CANARY));

        assertThat(output.getAll()).doesNotContain(KEY_CANARY);
    }

    private static void start(Map<String, Object> overrides) {
        var properties = new HashMap<String, Object>(Map.of(
                "server.port", "0",
                "orderhub.security.jwt.token-profile", "GENERIC",
                "orderhub.security.jwt.issuer", "https://issuer.example.test",
                "orderhub.security.jwt.audience", "orderhub-api",
                "orderhub.security.jwt.jwk-set-uri", "http://127.0.0.1:1/unused",
                "spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/unreachable"));
        properties.putAll(overrides);

        // Command-line arguments outrank profile files, mirroring how the
        // deployment environment would override the committed mount paths.
        var arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);

        try (var ignored = new SpringApplicationBuilder(OrderHubApplication.class)
                .profiles("retained")
                .run(arguments)) {
            throw new AssertionError("Retained context started without usable TLS material");
        }
    }

    private static String stackTrace(Throwable failure) {
        var writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
