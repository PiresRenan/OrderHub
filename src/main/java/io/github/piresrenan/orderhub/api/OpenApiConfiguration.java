package io.github.piresrenan.orderhub.api;

import java.util.List;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestBody;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/** Derives documentation from MVC adapters; common metadata is limited to actual transport behavior. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfiguration {
    /** Applies wire-specific corrections after springdoc has finished reflecting response components. */
    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer wireSchemaContract(
            @org.springframework.beans.factory.annotation.Value("${orderhub.orders.http.max-items}") int maxOrderItems) {
        return api -> {
            HttpSchemaContract.complete(api, maxOrderItems);
            api.getComponents().addSchemas("ProblemDetail", orderHubOpenApi().getComponents().getSchemas().get("ProblemDetail"));
        };
    }

    /** Gives nested issued/replay and item records distinct names while retaining their real reflected fields. */
    @Bean
    io.swagger.v3.core.jackson.ModelResolver contractModelResolver() {
        var names = new io.swagger.v3.core.jackson.TypeNameResolver() {
            /** Qualifies nested wire types by their owning type to prevent unrelated records sharing a schema. */
            @Override
            protected String getNameOfClass(Class<?> type) {
                return type.getEnclosingClass() == null ? super.getNameOfClass(type)
                        : type.getEnclosingClass().getSimpleName() + type.getSimpleName();
            }
        };
        return new io.swagger.v3.core.jackson.ModelResolver(io.swagger.v3.core.util.Json31.mapper(), names).openapi31(true);
    }

    /** Defines bearer trust and safe shared error fields without persistence or principal representations. */
    @Bean
    OpenAPI orderHubOpenApi() {
        var validation = new ObjectSchema()
                .addProperty("field", new StringSchema().description("Rejected field name; never its value."))
                .addProperty("code", new StringSchema().description("Validation constraint code."))
                .addProperty("message", new StringSchema().description("Fixed public validation explanation."));
        var problem = new ObjectSchema()
                .description("RFC 9457 public error. Codes differ by boundary; fields never contain credentials, rejected values, SQL or stack traces.")
                .addProperty("type", new StringSchema().format("uri").description("Problem type URI, usually urn:orderhub:problem:<code>."))
                .addProperty("title", new StringSchema().description("Short public explanation."))
                .addProperty("status", new IntegerSchema().description("HTTP response status."))
                .addProperty("detail", new StringSchema().description("Sanitized public detail; clients use status and code."))
                .addProperty("instance", new StringSchema().format("uri-reference").description("Optional occurrence path; lifecycle uses a fixed privacy-safe path."))
                .addProperty("code", new StringSchema().description("Owner-defined stable machine-readable code, when present."))
                .addProperty("errors", new ArraySchema().items(validation).description("Present only for supported Bean Validation failures."));
        problem.setRequired(List.of("type", "title", "status"));
        return new OpenAPI()
                .info(new Info().title("OrderHub API").version("0.1.0")
                        .description("Admitted v1 business surface; existing paths have no /v1 prefix. Stateless bearer authentication, owner-controlled authorization and PostgreSQL transactions. See the repository API and security guides for integration and retry contracts."))
                .servers(List.of(new Server().url("/").description("Current deployment; no client-supplied host is embedded.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSchemas("ProblemDetail", problem)
                        .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")
                                .description("Configured issuer, audience, signature and expiry are verified. Normal routes additionally require an active internal User binding; only the two bootstrap routes accept an unbound verified identity. JWT roles do not grant business permissions.")));
    }

    /** Adds shared transport failures while leaving owner-specific outcomes and successful schemas with their adapters. */
    @Bean
    OperationCustomizer transportResponseContract() {
        return (operation, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("io.github.piresrenan.orderhub")) return operation;
            var responses = operation.getResponses();
            responses.putIfAbsent("400", problemResponse("Malformed or invalid request metadata/body; private values are not reflected."));
            responses.putIfAbsent("401", problemResponse("Missing, invalid or unbound bearer authentication. WWW-Authenticate: Bearer."));
            responses.putIfAbsent("403", problemResponse("Current actor, Tenant membership or owner permission does not admit this operation."));
            responses.putIfAbsent("405", problemResponse("HTTP method is not supported. The Allow header identifies supported methods."));
            responses.putIfAbsent("406", problemResponse("Requested response media type is not supported."));
            responses.putIfAbsent("500", problemResponse("Technical uncertainty; sanitized failure without implementation details."));
            for (var parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    responses.putIfAbsent("415", problemResponse("Request representation is not supported."));
                }
            }
            var controller = handler.getBeanType().getSimpleName();
            responses.get("401").addHeaderObject("WWW-Authenticate", new Header().schema(new StringSchema()._const("Bearer")));
            responses.get("405").addHeaderObject("Allow", new Header().description("HTTP methods supported by the requested path.").schema(new StringSchema()));
            if (controller.equals("IdentityBootstrapController")) {
                responses.get("401").setDescription("Missing or invalid bearer authentication. A valid verified external identity need not have an internal binding on these bootstrap routes.");
            }
            if (controller.equals("IdentityBootstrapController") || controller.equals("IdentityLifecycleController")) {
                responses.forEach((status, response) -> {
                    if (status.startsWith("2")) response.addHeaderObject("Cache-Control",
                            new Header().description("One-time credentials and account lifecycle results must not be cached.")
                                    .schema(new StringSchema()._const("no-store")));
                });
            }
            return operation;
        };
    }

    /** Refers every shared error to one bounded wire schema instead of copying exception classes. */
    private static ApiResponse problemResponse(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/problem+json",
                new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
    }
}
