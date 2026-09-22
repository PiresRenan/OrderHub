package io.github.piresrenan.orderhub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.context.TestPropertySource;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Why: public documentation must describe actual handlers; Covers: generated contract; Prevents: silent drift. */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
@TestPropertySource(properties = "orderhub.security.jwt.token-profile=GENERIC")
class OpenApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void generatedContractCoversEveryBusinessHandlerWithUniqueDescribedOperations() throws Exception {
        var document = document();
        var actual = new TreeSet<String>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("io.github.piresrenan.orderhub")
                    && handler.getBeanType().getSimpleName().endsWith("Controller")) {
                mapping.getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods()
                        .forEach(method -> actual.add(method.name().toLowerCase() + " " + path)));
            }
        });
        var documented = new TreeSet<String>();
        var operationIds = new HashSet<String>();
        document.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (!Set.of("get", "post", "put", "delete", "patch").contains(method.getKey())) return;
            documented.add(method.getKey() + " " + path.getKey());
            var operation = method.getValue();
            assertThat(operation.path("operationId").asString()).isNotBlank();
            assertThat(operationIds.add(operation.path("operationId").asString())).isTrue();
            assertThat(operation.path("summary").asString()).isNotBlank();
            assertThat(operation.path("description").asString()).isNotBlank();
            assertThat(operation.path("tags").isEmpty()).isFalse();
            assertThat(operation.path("responses").has("401")).isTrue();
        }));
        assertThat(actual).hasSize(60);
        assertThat(documented).containsExactlyElementsOf(actual);
        assertThat(document.path("openapi").asString()).startsWith("3.1.");
        assertThat(document.at("/info/version").asString()).isEqualTo("1.0.0");
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asString()).isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asString()).isEqualTo("bearer");
        assertThat(document.at("/servers/0/url").asString()).isEqualTo("/");
        assertThat(document.toString()).doesNotContain("TrustedActorContext", "AuthenticatedUserPrincipal", "VerifiedExternalIdentity", "\"keyDigest\"", "\"fingerprint\"", "jwk-set-uri");
        Files.createDirectories(Path.of("target", "contracts"));
        Files.writeString(Path.of("target", "contracts", "openapi.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }

    @Test
    void contractPreservesOrdersAndCredentialResponseSemantics() throws Exception {
        var document = document();
        var orders = document.at("/paths/~1orders/post");
        assertThat(orders.path("responses").propertyNames()).contains("201", "400", "401", "403", "409", "413", "422", "500", "503");
        assertThat(orders.at("/responses/503/headers/Retry-After").isMissingNode()).isFalse();
        assertThat(document.at("/components/schemas/ProblemDetail/properties/status").isMissingNode()).isFalse();
        assertThat(document.at("/paths/~1identity~1bootstrap~1staff/post/responses/200/headers/Cache-Control").isMissingNode()).isFalse();
        assertThat(document()).isEqualTo(document);
    }

    @Test
    void idempotencyKeySchemaMatchesRuntimeVisibleAsciiGrammar() throws Exception {
        var parameters =
                document()
                        .at("/paths/~1orders/post/parameters");

        JsonNode schema = null;

        for (var parameter : parameters) {
            if ("Idempotency-Key".equals(
                    parameter.path("name").asString())) {

                schema =
                        parameter.path("schema");

                break;
            }
        }

        if (schema == null) {
            throw new AssertionError(
                    "Idempotency-Key parameter schema must be documented");
        }

        assertThat(schema.path("minLength").asInt())
                .isEqualTo(1);

        assertThat(schema.path("maxLength").asInt())
                .isEqualTo(128);

        assertThat(schema.path("pattern").asString())
                .isNotBlank();

        var pattern =
                java.util.regex.Pattern.compile(
                        schema.path("pattern").asString());

        for (var value : List.of(
                "key",
                "order-key_123",
                "!".repeat(128),
                "~".repeat(128))) {

            assertThat(pattern.matcher(value).matches())
                    .as("accepted Idempotency-Key: %s", value)
                    .isTrue();
        }

        for (var value : List.of(
                "",
                "a".repeat(129),
                "order key",
                "order,key",
                "caf\u00E9",
                "key\n",
                "\tkey",
                "key\u007F")) {

            assertThat(pattern.matcher(value).matches())
                    .as("rejected Idempotency-Key: %s", value)
                    .isFalse();
        }
    }

    @Test
    void exactNumbersAndFlattenedProblemsMatchActualWireRepresentations() throws Exception {
        var schemas = document().path("components").path("schemas");
        for (var name : List.of("CatalogPriceRequest", "CatalogPriceView")) {
            assertThat(schemas.path(name).path("properties").path("minorUnits").path("type").asString()).isEqualTo("integer");
        }
        schemas.properties().forEach(component -> component.getValue().path("properties").properties().forEach(field -> {
            if (Set.of("int32", "int64").contains(field.getValue().path("format").asString())) {
                assertThat(field.getValue().path("type").asString()).as("%s.%s", component.getKey(), field.getKey()).isEqualTo("integer");
            }
        }));
        assertThat(schemas.path("CatalogPriceRequest").path("additionalProperties").asBoolean(true)).isFalse();
        var listParameters = document().at("/paths/~1catalog~1categories/get/parameters");
        for (var parameter : listParameters) {
            if (parameter.path("name").asString().equals("limit")) {
                assertThat(parameter.path("schema").path("default").isIntegralNumber()).isTrue();
                assertThat(parameter.path("schema").path("default").asInt()).isEqualTo(50);
            }
        }
        assertThat(schemas.path("ProblemDetail").path("properties").propertyNames()).contains("code", "errors").doesNotContain("properties");
        assertThat(schemas.path("CatalogVariantCreate").path("properties").path("sku").path("maxLength").asInt()).isEqualTo(64);
        for (var family : List.of("StaffProvisioningIssuance", "ExternalIdentityLinkIssuance", "CustomerLinkIssuance")) {
            var issued = schemas.path(family + "Issued");
            var replay = schemas.path(family + "Replay");
            assertThat(issued.path("required").toString()).contains("credential", "expiresAt");
            assertThat(replay.path("required").size()).isEqualTo(1);
            assertThat(replay.path("additionalProperties").isBoolean()).isTrue();
            assertThat(replay.path("additionalProperties").asBoolean()).isFalse();
            assertThat(issued.path("discriminator").isMissingNode()).isTrue();
        }
    }

    @Test
    void administrativeNamePatternBoundsNormalizedUnicodeWithoutRejectingAcceptedPadding() throws Exception {
        var name = document().at("/components/schemas/AdministrativeNameRequest/properties/name");
        var pattern = java.util.regex.Pattern.compile(name.path("pattern").asString());
        assertThat(name.path("pattern").asString()).isNotBlank();
        var validator = new io.github.piresrenan.orderhub.administration.web.NormalizedAdministrativeName.Validator();
        for (var input : List.of("", " ", "a", "a".repeat(120), "a".repeat(121),
                " " + "😀".repeat(120) + " ", " " + "😀".repeat(121) + " ",
                "\u2000" + "a".repeat(120) + "\u3000", "\u00A0" + "a".repeat(120),
                "\u2007" + "a".repeat(120), "\u202F" + "a".repeat(120))) {
            assertThat(pattern.matcher(input).matches()).as("Normalized code point count %s", input.strip().codePointCount(0, input.strip().length()))
                    .isEqualTo(validator.isValid(input, null));
        }
    }

    @Test
    void catalogNormalizedTextMatchesOwnerValidation() throws Exception {
        var schemas = document().path("components").path("schemas");
        var id = java.util.UUID.fromString("02100000-0000-4000-8000-000000000001");
        var samples = java.util.Arrays.asList(null, "", " ", "a", "\u00A0a\u00A0", "\ta", "a\u0085",
                " " + "😀".repeat(120) + " ", "😀".repeat(121), " " + "😀".repeat(160) + " ", "😀".repeat(161));
        for (var value : samples) {
            for (var name : List.of("CatalogProductCreate", "CatalogProductUpdate")) {
                assertTextParity(schemas.path(name).path("properties").path("brand"), value,
                        () -> io.github.piresrenan.orderhub.catalog.domain.model.Product.create(id, id, "Name", "slug", null, value, List.of()));
                assertTextParity(schemas.path(name).path("properties").path("name"), value, () -> {
                    new io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogProductMetadata(value, "slug", null, null);
                    io.github.piresrenan.orderhub.catalog.domain.model.Product.create(id, id, value, "slug", null, List.of());
                });
            }
            for (var name : List.of("CatalogVariantCreate", "CatalogVariantUpdate")) {
                assertTextParity(schemas.path(name).path("properties").path("displayName"), value,
                        () -> io.github.piresrenan.orderhub.catalog.domain.model.ProductVariant.create(id, id, id, "SKU", value, null, null, List.of()));
            }
        }
    }

    private static void assertTextParity(JsonNode schema, String value, Runnable ownerValidation) {
        boolean admitted = true;
        try { ownerValidation.run(); } catch (IllegalArgumentException failure) { admitted = false; }
        boolean described = value == null ? schema.path("type").toString().contains("null")
                : value.codePointCount(0, value.length()) >= schema.path("minLength").asInt(0)
                && value.codePointCount(0, value.length()) <= schema.path("maxLength").asInt(Integer.MAX_VALUE)
                && java.util.regex.Pattern.compile(schema.path("pattern").asString()).matcher(value).matches();
        assertThat(described).as("Catalog owner/string contract parity").isEqualTo(admitted);
    }

    @Test
    void swaggerUsesLocalContractAndNeverPersistsAuthorization() throws Exception {
        var settings = json.readTree(mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(settings.path("persistAuthorization").asBoolean()).isFalse();
        assertThat(settings.path("validatorUrl").asString()).isEmpty();
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/orders")).andExpect(status().isUnauthorized());
    }

    private JsonNode document() throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
