package io.github.piresrenan.orderhub.platform.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies the committed retained transport contract (OH-027, ADR-0023) in the
 * Kubernetes base, image and local topologies without a running cluster.
 */
class RetainedTransportManifestTest {

    private static final Path DEPLOYMENT = Path.of("infra/kubernetes/base/deployment.yaml");
    private static final Path SERVICE = Path.of("infra/kubernetes/base/service.yaml");
    private static final Path CONFIG_MAP = Path.of("infra/kubernetes/base/configmap.yaml");
    private static final Path RETAINED_PROFILE =
            Path.of("src/main/resources/application-retained.properties");
    private static final Path SHARED_PROPERTIES =
            Path.of("src/main/resources/application.properties");
    private static final Path COMPOSE = Path.of("compose.yaml");
    private static final Path DOCKERFILE = Path.of("Dockerfile");

    /**
     * Why: the BFF-facing Service is the retained transport authority.
     * Covers: a single port named https, 8443, targeting the TLS container port.
     * Prevents: a consumable plaintext http:// application port on the Service.
     */
    @Test
    void serviceExposesOnlyHttps() throws IOException {
        var spec = map(yaml(SERVICE).get("spec"));
        var ports = list(spec.get("ports"));

        assertThat(spec.get("type")).isEqualTo("ClusterIP");
        assertThat(ports).hasSize(1);
        assertThat(map(ports.getFirst()))
                .containsEntry("name", "https")
                .containsEntry("port", 8443)
                .containsEntry("targetPort", "https");
    }

    /**
     * Why: the retained workload must terminate TLS itself and fail closed.
     * Covers: only the https container port, retained profile pinned by explicit
     * env, HTTPS probes on the TLS listener, required read-only Secret mount.
     * Prevents: HTTP listeners, profile omission, optional or writable TLS inputs.
     */
    @Test
    void deploymentServesOnlyTlsFromRequiredReadOnlySecret() throws IOException {
        var pod = map(map(map(yaml(DEPLOYMENT).get("spec")).get("template")).get("spec"));
        var container = map(list(pod.get("containers")).getFirst());

        assertThat(list(container.get("ports"))).containsExactly(
                Map.of("name", "https", "containerPort", 8443, "protocol", "TCP"));
        assertThat(list(container.get("env"))).contains(
                Map.of("name", "SPRING_PROFILES_ACTIVE", "value", "retained"));

        for (var probe : List.of("startupProbe", "readinessProbe", "livenessProbe")) {
            var httpGet = map(map(container.get(probe)).get("httpGet"));
            assertThat(httpGet).containsEntry("scheme", "HTTPS").containsEntry("port", "https");
        }
        assertThat(map(map(container.get("startupProbe")).get("httpGet")))
                .containsEntry("path", "/livez");
        assertThat(map(map(container.get("readinessProbe")).get("httpGet")))
                .containsEntry("path", "/readyz");
        assertThat(map(map(container.get("livenessProbe")).get("httpGet")))
                .containsEntry("path", "/livez");

        assertThat(list(container.get("volumeMounts"))).contains(Map.of(
                "name", "orderhub-tls", "mountPath", "/etc/orderhub/tls", "readOnly", true));
        var tlsVolume = list(pod.get("volumes")).stream().map(RetainedTransportManifestTest::map)
                .filter(volume -> "orderhub-tls".equals(volume.get("name")))
                .findFirst().orElseThrow();
        assertThat(map(tlsVolume.get("secret")))
                .containsEntry("secretName", "orderhub-tls")
                .containsEntry("optional", false)
                .containsEntry("defaultMode", 0440);
    }

    /**
     * Why: TLS must not weaken the accepted resiliency/security envelope.
     * Covers: replicas, rollout, grace, pod and container security context.
     * Prevents: root, writable root filesystem or token mount to support TLS.
     */
    @Test
    void deploymentPreservesOperationalEnvelope() throws IOException {
        var spec = map(yaml(DEPLOYMENT).get("spec"));
        var pod = map(map(spec.get("template")).get("spec"));
        var container = map(list(pod.get("containers")).getFirst());

        assertThat(spec).containsEntry("replicas", 2).containsEntry("minReadySeconds", 3);
        assertThat(map(map(spec.get("strategy")).get("rollingUpdate")))
                .containsEntry("maxUnavailable", 0).containsEntry("maxSurge", 1);
        assertThat(pod)
                .containsEntry("automountServiceAccountToken", false)
                .containsEntry("terminationGracePeriodSeconds", 30);
        assertThat(map(pod.get("securityContext")))
                .containsEntry("runAsNonRoot", true)
                .containsEntry("runAsUser", 10001)
                .containsEntry("fsGroup", 10001);
        assertThat(map(container.get("securityContext")))
                .containsEntry("readOnlyRootFilesystem", true)
                .containsEntry("allowPrivilegeEscalation", false);
    }

    /**
     * Why: key material belongs only in a deployment-owned Secret.
     * Covers: the committed ConfigMap and all base manifests.
     * Prevents: PEM material or TLS paths leaking into ConfigMaps or Git.
     */
    @Test
    void noTlsMaterialIsCommittedToManifests() throws IOException {
        assertThat(Files.readString(CONFIG_MAP)).doesNotContainIgnoringCase("tls");
        for (var manifest : List.of(DEPLOYMENT, SERVICE, CONFIG_MAP)) {
            assertThat(Files.readString(manifest)).doesNotContain("-----BEGIN");
        }
    }

    /**
     * Why: the retained profile is the only path that enables TLS and it must
     * have no plaintext fallback.
     * Covers: bundle selection, port and fixed mount paths; the shared file and
     * Compose never activate TLS or the retained profile.
     * Prevents: retained/local confusion in either direction.
     */
    @Test
    void retainedProfileIsTheExplicitTlsAuthority() throws IOException {
        assertThat(Files.readString(RETAINED_PROFILE))
                .contains("server.port=8443")
                .contains("server.ssl.bundle=orderhub-server")
                .contains("spring.ssl.bundle.pem.orderhub-server.keystore.certificate="
                        + "file:/etc/orderhub/tls/tls.crt")
                .contains("spring.ssl.bundle.pem.orderhub-server.keystore.private-key="
                        + "file:/etc/orderhub/tls/tls.key")
                .doesNotContain("reload-on-update=true");
        assertThat(Files.readString(SHARED_PROPERTIES))
                .doesNotContain("server.ssl", "spring.ssl", "server.port");
        assertThat(Files.readString(COMPOSE))
                .doesNotContain("retained", "SPRING_PROFILES_ACTIVE", "8443");
    }

    /**
     * Why: image metadata must truthfully describe both listeners.
     * Covers: Dockerfile EXPOSE declarations and absence of TLS material copies.
     * Prevents: stale metadata and baked-in certificates or keys.
     */
    @Test
    void imageDeclaresBothListenersWithoutTlsMaterial() throws IOException {
        var dockerfile = Files.readString(DOCKERFILE);

        assertThat(dockerfile).contains("EXPOSE 8080 8443");
        assertThat(dockerfile).doesNotContain("tls", ".crt", ".key", ".pem");
    }

    /**
     * Why: both Kubernetes validation profiles run the HTTPS-only base.
     * Covers: CI supplying the TLS Secret per namespace, HTTPS Service proxy
     * checks, and the wrong-CA/wrong-hostname/rotation qualification.
     * Prevents: platform validation silently reverting to the http port.
     */
    @Test
    void platformCiQualifiesRetainedHttpsInBothProfiles() throws IOException {
        var workflow = Files.readString(Path.of(".github/workflows/platform-ci.yml"));

        assertThat(workflow)
                .contains("--namespace orderhub-local \\\n            --cert")
                .contains("--namespace orderhub-scale \\\n            --cert")
                .contains("services/https:orderhub:8443/proxy/readyz")
                .contains("foreign-ca.crt", "orderhub.wrong-namespace.svc",
                        "kubectl rollout restart", "OrderHub became available without its TLS Secret")
                .doesNotContain("services/http:orderhub:8080", "--insecure", "curl -k");
    }

    private static Map<String, Object> yaml(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
