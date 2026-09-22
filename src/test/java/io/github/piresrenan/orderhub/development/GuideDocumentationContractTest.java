package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Why: the usage and integration guides are the contract new teams read first.
 * Covers: documented operationIds, routes, personas, manifest fields, seed safety warnings, the deliberate absence of
 * machine-to-machine capability and the critical status vocabulary, checked against the product and the seed catalog.
 * Prevents: guides drifting into invented endpoints, stale personas or fictional M2M/API-key integration advice.
 * Lives beside the seed because it reads the package-private development catalog.
 */
class GuideDocumentationContractTest {
    private static final Path GUIDES = Path.of("docs", "guides");
    private static final List<String> FILES = List.of("system-usage.md", "frontend-integration.md",
            "application-integration.md");
    private static final Pattern OPERATION = Pattern.compile(
            "`((?:catalog|inventory|orders|identity|platform|organization)[A-Z][A-Za-z]+)`");
    private static final Pattern ROUTE = Pattern.compile(
            "(?<![A-Za-z0-9./])(/(?:orders|catalog|inventory|platform|organizations|identity|administration|tenants)[A-Za-z0-9{}/\\-]*)");

    @Test
    void guidesOnlyReferenceOperationsAndRoutesThatExist() throws Exception {
        var sources = new StringBuilder();
        var served = new java.util.TreeSet<String>();
        try (var files = Files.walk(Path.of("src", "main", "java", "io", "github", "piresrenan", "orderhub"))) {
            for (var file : files.filter(path -> path.toString().endsWith("Controller.java")).toList()) {
                var controller = Files.readString(file);
                sources.append(controller);
                // Handlers compose a class-level base mapping with relative method mappings.
                var base = Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"").matcher(controller);
                var prefix = base.find() ? base.group(1) : "";
                var mapping = Pattern.compile("@(?:Get|Post|Put|Delete)Mapping\\(([^)]*)\\)", Pattern.DOTALL)
                        .matcher(controller);
                while (mapping.find()) {
                    // A handler may map a relative path or inherit the class-level base mapping alone.
                    var path = Pattern.compile("\"(/[^\"]*)\"").matcher(mapping.group(1));
                    served.add(normalize(prefix + (path.find() ? path.group(1) : "")));
                }
            }
        }
        var checkedRoutes = 0;
        for (var file : FILES) {
            var text = Files.readString(GUIDES.resolve(file));
            var operations = OPERATION.matcher(text);
            while (operations.find()) {
                assertThat(sources.toString()).as("%s references operationId %s", file, operations.group(1))
                        .contains("operationId = \"" + operations.group(1) + "\"");
            }
            var routes = ROUTE.matcher(text);
            while (routes.find()) {
                var route = routes.group(1).replaceAll("/$", "");
                // Compare the literal segments; path variable names differ between docs and handlers.
                var mapped = route.replaceAll("\\{[A-Za-z]+}", "{}");
                assertThat(served).as("%s documents route %s", file, route).contains(mapped);
                checkedRoutes++;
            }
        }
        assertThat(checkedRoutes).isGreaterThan(20);
    }

    @Test
    void guidesDescribeRealPersonasManifestFieldsAndSeedSafety() throws Exception {
        var usage = Files.readString(GUIDES.resolve("system-usage.md"));
        assertThat(usage).contains("`INITIAL_TENANT_GOVERNANCE_V1`");
        for (var persona : List.of("staff", "customer", "platform", "unbound")) {
            assertThat(DevelopmentSeedCatalog.PERSONA_NAMES).contains(persona);
        }
        var personaMentions = new ArrayList<String>();
        var mentions = Pattern.compile("`(alpha-[a-z0-9-]+|beta-[a-z-]+|multi-tenant-staff|org-viewer|outsider|unbound)`")
                .matcher(usage + Files.readString(GUIDES.resolve("frontend-integration.md")));
        while (mentions.find()) personaMentions.add(mentions.group(1));
        assertThat(DevelopmentSeedCatalog.PERSONA_NAMES).containsAll(personaMentions);
        for (var field : List.of("personas", "organizations", "tenants", "customers", "catalog", "inventory", "orders")) {
            assertThat(usage).as("manifest field %s", field).contains(field);
            assertThat(DevelopmentSeedContractTest.MANIFEST_FIELDS).contains(field);
        }
        // The development dataset must always be introduced as development-only.
        assertThat(usage).contains("not a production provisioning mechanism");
        assertThat(Files.readString(Path.of("docs", "development", "seed-data.md")))
                .contains("SYNTHETIC / DEVELOPMENT ONLY / NEVER PRODUCTION");
    }

    @Test
    void guidesStateTheMachineIdentityLimitAndNeverSuggestSubstitutes() throws Exception {
        // Markdown wraps lines, so statements are compared on whitespace-normalized text.
        var application = Files.readString(GUIDES.resolve("application-integration.md")).replaceAll("\\s+", " ");
        assertThat(application).contains("no independent machine identity").contains("post-v1");
        for (var file : FILES) {
            var text = Files.readString(GUIDES.resolve(file));
            for (var fiction : List.of("client_credentials grant is supported", "use an API key", "X-Api-Key",
                    "service account token", "scope grants Staff")) {
                assertThat(text).as("%s suggests %s", file, fiction).doesNotContain(fiction);
            }
        }
    }

    @Test
    void guidesDocumentTheCriticalStatusVocabulary() throws Exception {
        for (var file : List.of("system-usage.md", "frontend-integration.md", "application-integration.md")) {
            var text = Files.readString(GUIDES.resolve(file));
            for (var status : List.of("401", "403", "404", "409", "422", "503")) {
                assertThat(text).as("%s documents %s", file, status).contains(status);
            }
            assertThat(text).as("%s documents idempotency", file).containsIgnoringCase("idempotency");
        }
        assertThat(Files.readString(GUIDES.resolve("system-usage.md"))).contains("Retry-After");
    }

    /** Normalizes a path to the placeholder form used for comparison; variable names differ between docs and handlers. */
    private static String normalize(String path) {
        return path.replaceAll("\\{[A-Za-z]+}", "{}").replaceAll("/$", "");
    }
}
