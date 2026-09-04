package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.ViewCustomerOrderUseCase;
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
class OrderIdempotencyKeyHttpContractTest {

    private static final String IDEMPOTENCY_KEY =
            "Idempotency-Key";

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateCustomerOrderUseCase createCustomerOrderUseCase;

    @MockitoBean
    private ViewCustomerOrderUseCase viewCustomerOrderUseCase;

    @MockitoBean
    private ResolveTrustedTenantContextUseCase trustedTenants;

    @BeforeEach
    void configureExistingPostOrderBehavior() {

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

        /*
         * If a request reaches the application use case, make it succeed.
         *
         * This is intentional for RED:
         * missing/invalid Idempotency-Key currently reaches Orders and therefore
         * produces 201 instead of the required 400.
         */
        when(createCustomerOrderUseCase.create(
                any(UUID.class),
                any(CreateOrderCommand.class)))
                .thenAnswer(invocation -> {

                    var command =
                            invocation.getArgument(
                                    1,
                                    CreateOrderCommand.class);

                    var orderItems =
                            command.items()
                                    .stream()
                                    .map(item ->
                                            new OrderItem(
                                                    item.variantId(),
                                                    item.quantity()))
                                    .toList();

                    var order =
                            Order.create(
                                    ORDER_ID,
                                    command.tenantId(),
                                    command.customerId(),
                                    orderItems);

                    return new CreateOrderResult(
                            order,
                            CreateOrderAllocationOutcome.FULLY_ALLOCATED);
                });
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {

        var tenantId =
                UUID.randomUUID();

        mockMvc.perform(
                        authenticatedPost()
                                .header(
                                        "X-Tenant-Id",
                                        tenantId)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody()))
                .andExpect(
                        status().isBadRequest())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.type")
                                .value(
                                        "urn:orderhub:problem:idempotency-key-invalid"))
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "IDEMPOTENCY_KEY_INVALID"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "The request idempotency key is missing or invalid."));

        verifyNoInteractions(
                createCustomerOrderUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidKeys")
    void rejectsInvalidIdempotencyKey(
            String scenario,
            String invalidKey) throws Exception {

        var tenantId =
                UUID.randomUUID();

        mockMvc.perform(
                        authenticatedPost()
                                .header(
                                        "X-Tenant-Id",
                                        tenantId)
                                .header(
                                        IDEMPOTENCY_KEY,
                                        invalidKey)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody()))
                .andExpect(
                        status().isBadRequest())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.type")
                                .value(
                                        "urn:orderhub:problem:idempotency-key-invalid"))
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "IDEMPOTENCY_KEY_INVALID"));

        verifyNoInteractions(
                createCustomerOrderUseCase);
    }

    @Test
    void rejectsMultipleIdempotencyKeyHeaderValues()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        mockMvc.perform(
                        authenticatedPost()
                                .header(
                                        "X-Tenant-Id",
                                        tenantId)
                                .header(
                                        IDEMPOTENCY_KEY,
                                        "first-key",
                                        "second-key")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody()))
                .andExpect(
                        status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "IDEMPOTENCY_KEY_INVALID"));

        verifyNoInteractions(
                createCustomerOrderUseCase);
    }

    @Test
    void doesNotEchoRejectedIdempotencyKey()
            throws Exception {

        var tenantId =
                UUID.randomUUID();

        var privateMarker =
                "synthetic-private-idempotency-key with-space";

        mockMvc.perform(
                        authenticatedPost()
                                .header(
                                        "X-Tenant-Id",
                                        tenantId)
                                .header(
                                        IDEMPOTENCY_KEY,
                                        privateMarker)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody()))
                .andExpect(
                        status().isBadRequest())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "IDEMPOTENCY_KEY_INVALID"))
                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                privateMarker))));

        verifyNoInteractions(
                createCustomerOrderUseCase);
    }

    private static Stream<Arguments> invalidKeys() {

        return Stream.of(
                Arguments.of(
                        "contains whitespace",
                        "client key"),
                Arguments.of(
                        "contains comma",
                        "client,key"),
                Arguments.of(
                        "contains non-ASCII",
                        "client-\u00e9"),
                Arguments.of(
                        "exceeds 128 characters",
                        "a".repeat(129)));
    }

    private static MockHttpServletRequestBuilder authenticatedPost() {

        return post("/orders")
                .principal(
                        new AuthenticatedUserAuthenticationToken(
                                new AuthenticatedUserPrincipal(
                                        UUID.fromString(
                                                "11111111-1111-1111-1111-111111111111"))));
    }

    private static String validBody() {

        return """
                {
                  "customerId": "22222222-2222-2222-2222-222222222222",
                  "items": [
                    {
                      "variantId": "33333333-3333-3333-3333-333333333333",
                      "quantity": 2
                    }
                  ]
                }
                """;
    }
}
