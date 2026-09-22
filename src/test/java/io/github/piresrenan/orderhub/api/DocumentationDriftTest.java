package io.github.piresrenan.orderhub.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Why: onboarding must stay executable; Covers: local guide links/config keys; Prevents: silent documentation drift. */
class DocumentationDriftTest {
    @Test
    void currentEngineeringGuidesHaveResolvableLocalLinks() throws Exception {
        var guides = List.of("README.md", "docs/testing.md", "docs/api/README.md", "docs/architecture/overview.md",
                "docs/development/local-runtime.md", "docs/development/seed-data.md", "docs/operations/README.md", "docs/operations/configuration.md",
                "docs/operations/migrations.md", "docs/security/README.md", "docs/integration/README.md");
        var links = Pattern.compile("\\[[^\\]]*]\\(([^)]+)\\)");
        for (var guide : guides) {
            var file = Path.of(guide);
            var matches = links.matcher(Files.readString(file));
            while (matches.find()) {
                var target = matches.group(1);
                if (target.startsWith("#") || target.matches("^[a-zA-Z]+:.*")) continue;
                target = target.split("#", 2)[0];
                var parent = file.getParent() == null ? Path.of(".") : file.getParent();
                assertThat(Files.exists(parent.resolve(target).normalize())).as("%s -> %s", guide, target).isTrue();
            }
        }
    }

    @Test
    void everyDeclaredRuntimeKeyAndComposeVariableHasAReference() throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { properties.load(reader); }
        var reference = Files.readString(Path.of("docs/operations/configuration.md"));
        properties.stringPropertyNames().forEach(key -> assertThat(reference).as("Operational key %s", key).contains("`" + key + "`"));
        var compose = Files.readString(Path.of("compose.yaml"));
        var example = Files.readString(Path.of(".env.example"));
        var variables = Pattern.compile("\\$\\{(ORDERHUB_[A-Z0-9_]+)").matcher(compose);
        while (variables.find()) assertThat(example).contains(variables.group(1) + "=");
    }
}
