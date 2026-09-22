package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Why: seed documentation is the onboarding contract for new developers.
 * Covers: documented personas, manifest fields and journey operations against the real catalog and product, plus
 * deployment documentation and committed files free of seed guidance, tokens and private keys.
 * Prevents: stale persona tables, invented operations or a leaked development credential.
 */
class DevelopmentSeedDocumentationTest {
    private static final Path GUIDE = Path.of("docs", "development", "seed-data.md");
    // Assembled so this source file does not itself contain the marker it searches for.
    private static final String PEM_PRIVATE_KEY = "PRIVATE" + " KEY" + "-----";
    private static final Pattern PERSONA_ROW = Pattern.compile("(?m)^\\| `([a-z0-9-]+)` \\| ");
    private static final Pattern OPERATION = Pattern.compile("`((?:catalog|inventory|orders|identity|platform|organization)[A-Z][A-Za-z]+)`");

    @Test
    void personaTableMatchesTheSeedCatalogExactly() throws Exception {
        var personas = new ArrayList<String>();
        var rows = PERSONA_ROW.matcher(section("## Personas", "## Organizations and Tenants"));
        while (rows.find()) personas.add(rows.group(1));
        assertThat(personas).containsExactlyElementsOf(DevelopmentSeedCatalog.PERSONA_NAMES);
    }

    @Test
    void everyManifestFieldIsDocumentedInOrder() throws Exception {
        var manifest = section("## Manifest fields", "## Personas");
        var position = -1;
        for (var field : DevelopmentSeedContractTest.MANIFEST_FIELDS) {
            var next = manifest.indexOf("`" + field + "`");
            assertThat(next).as("documented manifest field %s", field).isGreaterThan(position);
            position = next;
        }
    }

    @Test
    void documentedJourneyOperationsExistInTheProduct() throws Exception {
        var sources = new StringBuilder();
        try (var files = Files.walk(Path.of("src", "main", "java", "io", "github", "piresrenan", "orderhub"))) {
            for (var file : files.filter(path -> path.toString().endsWith("Controller.java")).toList()) {
                sources.append(Files.readString(file));
            }
        }
        var operations = OPERATION.matcher(section("## Journeys", null));
        var found = 0;
        while (operations.find()) {
            found++;
            assertThat(sources.toString()).as("operationId %s", operations.group(1))
                    .contains("operationId = \"" + operations.group(1) + "\"");
        }
        assertThat(found).isGreaterThan(5);
    }

    @Test
    void deploymentDocumentationNeverRecommendsTheSeed() throws Exception {
        var deployment = new ArrayList<Path>(List.of(Path.of("compose.yaml"), Path.of("Dockerfile"), Path.of(".env.example"),
                Path.of("docs", "integration", "README.md"), Path.of("docs", "security", "README.md")));
        for (var root : List.of(Path.of("docs", "operations"), Path.of("infra"))) {
            try (var files = Files.walk(root)) {
                deployment.addAll(files.filter(Files::isRegularFile).toList());
            }
        }
        for (var file : deployment) {
            var text = Files.readString(file);
            for (var marker : List.of("LocalDevelopmentApplication", "spring-boot:test-run", "/tokens/", "seed-data.md")) {
                assertThat(text).as("%s mentions %s", file, marker).doesNotContain(marker);
            }
            for (var persona : DevelopmentSeedCatalog.PERSONA_NAMES) {
                assertThat(text).as("%s names a seed subject", file).doesNotContain(DevelopmentIssuer.subject(persona));
            }
        }
    }

    @Test
    void noBearerTokenOrPrivateKeyIsCommitted() throws Exception {
        var jwt = Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.");
        for (var root : List.of(Path.of("docs"), Path.of("src"), Path.of("scripts"), Path.of("README.md"))) {
            try (var files = Files.walk(root)) {
                for (var file : files.filter(Files::isRegularFile).filter(path -> !path.toString().endsWith(".class")).toList()) {
                    var text = Files.readString(file);
                    assertThat(jwt.matcher(text).find()).as("%s contains a bearer token", file).isFalse();
                    assertThat(text).as("%s contains a private key", file).doesNotContain(PEM_PRIVATE_KEY);
                }
            }
        }
    }

    private static String section(String start, String end) throws Exception {
        var text = Files.readString(GUIDE);
        var from = text.indexOf(start);
        assertThat(from).as("section %s", start).isNotNegative();
        var to = end == null ? text.length() : text.indexOf(end, from);
        assertThat(to).as("section end %s", end).isGreaterThan(from);
        return text.substring(from, to);
    }
}
