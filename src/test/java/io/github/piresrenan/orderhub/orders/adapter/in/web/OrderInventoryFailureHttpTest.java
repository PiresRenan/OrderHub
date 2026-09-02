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

import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryOperationException;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.security.support.TrustedTenantMvcTestConfiguration;

@WebMvcTest(OrderController.class)
@Import(TrustedTenantMvcTestConfiguration.class)
class OrderInventoryFailureHttpTest {

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
    void mapsInventoryCommitmentRejectionToPrivacySafeConflict()
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
                        new InventoryCommitmentRejectedException());

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
                                                "urn:orderhub:problem:inventory-commitment-rejected"))
                        .andExpect(
                                jsonPath("$.code")
                                        .value(
                                                "INVENTORY_COMMITMENT_REJECTED"))
                        .andReturn();

        assertThat(
                result.getResponse()
                        .getContentAsString())
                .doesNotContain(
                        tenantId.toString(),
                        customerId.toString(),
                        variantId.toString(),
                        "Insufficient inventory",
                        "policy",
                        "position");
    }

    @Test
    void mapsInventoryTechnicalFailureToExistingSanitizedInternalError()
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
                        new InventoryOperationException(
                                new IllegalStateException(
                                        "synthetic-inventory-storage-failure")));

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
                                jsonPath("$.code")
                                        .value(
                                                "INTERNAL_ERROR"))
                        .andReturn();

        assertThat(
                result.getResponse()
                        .getContentAsString())
                .doesNotContain(
                        tenantId.toString(),
                        customerId.toString(),
                        variantId.toString(),
                        "synthetic-inventory-storage-failure",
                        "InventoryPersistenceException");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authenticatedPost(
                    UUID tenantId) {

        var principal =
                new AuthenticatedUserPrincipal(
                        UUID.randomUUID());

        return post("/orders")
                .header(
                        "Idempotency-Key",
                        "existing-orders-contract-test-key")
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