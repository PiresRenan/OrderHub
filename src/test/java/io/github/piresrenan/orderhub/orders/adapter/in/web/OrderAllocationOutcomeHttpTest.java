package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.security.support.TrustedTenantMvcTestConfiguration;

@WebMvcTest(OrderController.class)
@Import(TrustedTenantMvcTestConfiguration.class)
class OrderAllocationOutcomeHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @MockitoBean
    private ResolveTrustedTenantContextUseCase trustedTenants;

    @BeforeEach
    void trustRequestedTenant() {

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

    @ParameterizedTest
    @EnumSource(CreateOrderAllocationOutcome.class)
    void exposesAllocationOutcomeSeparatelyFromCreatedOrderStatus(
            CreateOrderAllocationOutcome allocationOutcome)
            throws Exception {

        var orderId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var order =
                Order.create(
                        orderId,
                        tenantId,
                        customerId,
                        List.of(
                                new OrderItem(
                                        variantId,
                                        2)));

        when(createOrderUseCase.create(
                any(CreateOrderCommand.class)))
                .thenReturn(
                        new CreateOrderResult(
                                order,
                                allocationOutcome));

        mockMvc.perform(
                post("/orders")
                        .principal(
                                new AuthenticatedUserAuthenticationToken(
                                        new AuthenticatedUserPrincipal(
                                                UUID.fromString(
                                                        "11111111-1111-1111-1111-111111111111"))))
                        .header(
                                "X-Tenant-Id",
                                tenantId)
                        .contentType(
                                MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "%s",
                                  "items": [
                                    {
                                      "variantId": "%s",
                                      "quantity": 2
                                    }
                                  ]
                                }
                                """.formatted(
                                        customerId,
                                        variantId)))
                .andExpect(
                        status().isCreated())
                .andExpect(
                        jsonPath("$.status")
                                .value("CREATED"))
                .andExpect(
                        jsonPath("$.allocationOutcome")
                                .value(
                                        allocationOutcome.name()));
    }
}