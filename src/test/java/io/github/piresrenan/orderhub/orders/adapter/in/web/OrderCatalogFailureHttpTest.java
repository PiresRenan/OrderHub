package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityRejectedException;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityTechnicalException;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.security.support.TrustedTenantMvcTestConfiguration;

/**
 * HTTP privacy proof for Catalog orderability rejection.
 */
@WebMvcTest(OrderController.class)
@Import(TrustedTenantMvcTestConfiguration.class)
class OrderCatalogFailureHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @MockitoBean
    private ResolveTrustedTenantContextUseCase trustedTenants;

    @BeforeEach
    void allowRequestedTenant() {

        when(trustedTenants.resolve(
                any(ResolveTrustedTenantContextQuery.class)))
                .thenAnswer(invocation -> {

                    var query =
                            invocation.getArgument(
                                    0,
                                    ResolveTrustedTenantContextQuery.class);

                    return Optional.of(
                            new TrustedTenantContext(
                                    query.requestedTenantId()));
                });
    }

    @Test
    void mapsCatalogOrderabilityRejectionToNonEnumeratingConflict()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        when(createOrderUseCase.create(
                any(CreateOrderCommand.class)))
                .thenThrow(
                        new CatalogOrderabilityRejectedException());

        var result =
                mockMvc.perform(
                                authenticatedPost(
                                        tenantId)
                                        .content(
                                                body(
                                                        customerId,
                                                        variantId)))
                        .andExpect(
                                status().isConflict())
                        .andExpect(
                                content().contentTypeCompatibleWith(
                                        MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(
                                jsonPath("$.type")
                                        .value(
                                                "urn:orderhub:problem:order-not-accepted"))
                        .andExpect(
                                jsonPath("$.title")
                                        .value(
                                                "Order could not be accepted"))
                        .andExpect(
                                jsonPath("$.detail")
                                        .value(
                                                "The order could not be accepted."))
                        .andExpect(
                                jsonPath("$.code")
                                        .value(
                                                "ORDER_NOT_ACCEPTED"))
                        .andReturn();

        var responseBody =
                result.getResponse()
                        .getContentAsString();

        assertThat(responseBody)
                .doesNotContain(
                        tenantId.toString(),
                        customerId.toString(),
                        variantId.toString(),
                        "CatalogOrderabilityRejectedException",
                        "One or more Order items are unavailable.",
                        "DRAFT",
                        "INACTIVE",
                        "ARCHIVED",
                        "missing",
                        "other Tenant",
                        "product_id",
                        "variant_id");

        assertThat(responseBody.toLowerCase())
                .doesNotContain(
                        "catalog",
                        "product",
                        "variant",
                        "lifecycle");
    }

    @Test
    void mapsCatalogTechnicalFailureToExistingSanitizedInternalError()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        when(createOrderUseCase.create(
                any(CreateOrderCommand.class)))
                .thenThrow(
                        new CatalogOrderabilityTechnicalException(
                                new IllegalStateException(
                                        "synthetic-catalog-storage-failure jdbc sql")));

        var result =
                mockMvc.perform(
                                authenticatedPost(
                                        tenantId)
                                        .content(
                                                body(
                                                        customerId,
                                                        variantId)))
                        .andExpect(
                                status().isInternalServerError())
                        .andExpect(
                                content().contentTypeCompatibleWith(
                                        MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(
                                jsonPath("$.type")
                                        .value(
                                                "urn:orderhub:problem:internal-error"))
                        .andExpect(
                                jsonPath("$.title")
                                        .value(
                                                "Internal server error"))
                        .andExpect(
                                jsonPath("$.detail")
                                        .value(
                                                "The request could not be completed."))
                        .andExpect(
                                jsonPath("$.code")
                                        .value(
                                                "INTERNAL_ERROR"))
                        .andReturn();

        var responseBody =
                result.getResponse()
                        .getContentAsString();

        assertThat(responseBody)
                .doesNotContain(
                        tenantId.toString(),
                        customerId.toString(),
                        variantId.toString(),
                        "CatalogOrderabilityTechnicalException",
                        "Catalog orderability validation could not be completed.",
                        "synthetic-catalog-storage-failure",
                        "jdbc",
                        "sql",
                        "product_id",
                        "variant_id");

        assertThat(responseBody.toLowerCase())
                .doesNotContain(
                        "catalog",
                        "database",
                        "postgres",
                        "exception",
                        "stack");
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authenticatedPost(
                    UUID tenantId) {

        var principal =
                new AuthenticatedUserPrincipal(
                        UUID.randomUUID());

        return post("/orders")
                .principal(
                        new AuthenticatedUserAuthenticationToken(
                                principal))
                .header(
                        "X-Tenant-Id",
                        tenantId)
                .contentType(
                        MediaType.APPLICATION_JSON);
    }

    private static String body(
            UUID customerId,
            UUID variantId) {

        return """
                {
                  "customerId": "%s",
                  "items": [{
                    "variantId": "%s",
                    "quantity": 1
                  }]
                }
                """.formatted(
                        customerId,
                        variantId);
    }
}
