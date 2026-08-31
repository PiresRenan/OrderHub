package io.github.piresrenan.orderhub.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Verifies that deployable runtime surfaces provide the external JWT
 * configuration required by the Security boundary.
 */
class PlatformJwtRuntimeConfigurationTest {

    private static final Path COMPOSE_FILE =
            Path.of("compose.yaml");

    private static final Path KUBERNETES_DEPLOYMENT =
            Path.of("infra/kubernetes/base/deployment.yaml");

    private static final Path KUBERNETES_RUNTIME_CONFIG =
            Path.of("infra/kubernetes/base/configmap.yaml");

    private static final Path PLATFORM_CI =
            Path.of(".github/workflows/platform-ci.yml");

    /**
     * Verifies that Docker Compose requires JWT trust configuration from its
     * execution environment.
     *
     * <p>Why: OH-010 must fail closed when issuer, audience or JWK Set URI are
     * not supplied by the deployment environment.</p>
     *
     * <p>Covers: the Docker Compose boundary for the three mandatory JWT
     * configuration values.</p>
     *
     * <p>Prevents: silently restoring insecure application defaults or running
     * the Compose workload without an explicit token-validation policy.</p>
     */
    @Test
    void composeRequiresJwtTrustConfigurationFromRuntime() throws IOException {

        var compose = read(COMPOSE_FILE);

        assertThat(compose)
                .contains(
                        "ORDERHUB_SECURITY_JWT_ISSUER",
                        "ORDERHUB_SECURITY_JWT_AUDIENCE",
                        "ORDERHUB_SECURITY_JWT_JWK_SET_URI");
    }

    /**
     * Verifies that Kubernetes declares JWT trust configuration as an external
     * environment-owned contract.
     *
     * <p>Why: issuer, audience and JWK Set URI vary by environment and must not
     * be baked into the application image or the reusable base configuration.</p>
     *
     * <p>Covers: the Deployment dependency on an external security ConfigMap
     * and the absence of concrete JWT trust values from the committed base
     * runtime ConfigMap.</p>
     *
     * <p>Prevents: environment-specific identity-provider configuration from
     * becoming a product default.</p>
     */
    @Test
    void kubernetesConsumesExternalJwtSecurityConfiguration() throws IOException {

        var deployment = read(KUBERNETES_DEPLOYMENT);
        var runtimeConfig = read(KUBERNETES_RUNTIME_CONFIG);

        assertThat(deployment)
                .contains("name: orderhub-security");

        assertThat(runtimeConfig)
                .doesNotContain(
                        "ORDERHUB_SECURITY_JWT_ISSUER",
                        "ORDERHUB_SECURITY_JWT_AUDIENCE",
                        "ORDERHUB_SECURITY_JWT_JWK_SET_URI");
    }

    /**
     * Verifies that platform CI provides an explicit synthetic JWT trust policy
     * rather than relying on production defaults.
     *
     * <p>Why: platform-validation boots the real application image, so it must
     * satisfy the same mandatory runtime contract without depending on a live
     * external identity provider.</p>
     *
     * <p>Covers: explicit synthetic issuer, audience and JWK Set URI variables
     * in the platform workflow.</p>
     *
     * <p>Prevents: CI-only application fallbacks and accidental dependence on a
     * real OIDC service during platform smoke tests.</p>
     */
    @Test
    void platformCiDefinesSyntheticJwtTrustConfiguration() throws IOException {

        var workflow = read(PLATFORM_CI);

        assertThat(workflow)
                .contains(
                        "ORDERHUB_SECURITY_JWT_ISSUER:",
                        "ORDERHUB_SECURITY_JWT_AUDIENCE:",
                        "ORDERHUB_SECURITY_JWT_JWK_SET_URI:");
    }

    /**
     * Verifies that each disposable Kubernetes validation environment receives
     * the external security ConfigMap required by the base Deployment.
     *
     * <p>Why: both development and scale profiles consume the same reusable
     * Deployment contract in distinct namespaces.</p>
     *
     * <p>Covers: CI creation of the orderhub-security ConfigMap for both
     * Kubernetes validation profiles.</p>
     *
     * <p>Prevents: one profile passing while another remains unable to start
     * after Security becomes fail-closed.</p>
     */
    @Test
    void platformCiCreatesSecurityConfigurationForBothKubernetesProfiles()
            throws IOException {

        var workflow = read(PLATFORM_CI);

        assertThat(occurrences(workflow, "kubectl create configmap orderhub-security"))
                .isEqualTo(2);
    }

    /**
     * Reads a repository-owned text artifact used by a platform contract test.
     *
     * @param path repository-relative artifact path
     * @return complete artifact content
     * @throws IOException when the artifact cannot be read
     */
    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Counts non-overlapping occurrences of a platform contract marker.
     *
     * @param value complete source text
     * @param marker marker whose occurrences are counted
     * @return number of non-overlapping marker occurrences
     */
    private static int occurrences(String value, String marker) {

        var count = 0;
        var index = 0;

        while ((index = value.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }

        return count;
    }
}
