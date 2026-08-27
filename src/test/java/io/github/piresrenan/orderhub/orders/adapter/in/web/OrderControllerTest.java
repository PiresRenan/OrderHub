package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @Test
    void createsOrderFromValidRequest() throws Exception {
        // Why: establishes the canonical successful HTTP contract.
        // Covers: JSON binding, header mapping, command mapping and response serialization.
        // Prevents: silent contract drift between API clients and the application layer.

        var orderId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var order = Order.create(
                orderId,
                tenantId,
                customerId,
                List.of(new OrderItem(productId, 2)));

        when(createOrderUseCase.create(any(CreateOrderCommand.class)))
                .thenReturn(order);

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(customerId, productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.items[0].productId")
                        .value(productId.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        var captor = ArgumentCaptor.forClass(CreateOrderCommand.class);

        verify(createOrderUseCase).create(captor.capture());

        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().customerId()).isEqualTo(customerId);
        assertThat(captor.getValue().items()).hasSize(1);
    }

    @Test
    void rejectsMissingTenantHeader() throws Exception {
        // Why: requests without tenant context must never reach business processing.
        // Covers: mandatory request metadata.
        // Prevents: creation of future unscoped or ambiguous tenant data.

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code")
                        .value("REQUEST_BINDING_FAILED"));

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsInvalidTenantUuid() throws Exception {
        // Why: syntactically invalid tenant identifiers are common client/input errors.
        // Covers: HTTP type conversion.
        // Prevents: malformed identity values propagating into application logic.

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsMissingBody() throws Exception {
        // Why: empty requests can originate from broken clients, proxies or integrations.
        // Covers: mandatory JSON body handling.
        // Prevents: null access and ambiguous application failures.

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        // Why: malformed payloads are common at public API boundaries.
        // Covers: JSON parser failures.
        // Prevents: parser internals leaking to clients or business code receiving garbage.

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        // Why: explicit media contracts avoid ambiguous parser behavior.
        // Covers: Content-Type enforcement.
        // Prevents: accidental handling of unsupported representations.

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("invalid"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code")
                        .value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(createOrderUseCase);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequestBodies")
    void rejectsStructurallyInvalidOrders(
            String scenario,
            String requestBody) throws Exception {

        // Why: every malformed field combination must stop at the API boundary.
        // Covers: Jakarta Bean Validation including nested collection elements.
        // Prevents: unnecessary domain/database work and inconsistent client behavior.

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsUnknownJsonProperties() throws Exception {
        // Why: silently ignored fields hide typos and API contract drift.
        // Covers: strict JSON deserialization.
        // Prevents: clients believing that an ignored field was processed.

        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var body = """
                {
                  "customerId": "%s",
                  "unexpectedField": "ignored-by-loose-parsers",
                  "items": [
                    {
                      "productId": "%s",
                      "quantity": 2
                    }
                  ]
                }
                """.formatted(customerId, productId);

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void doesNotEchoRejectedValuesInErrorResponses() throws Exception {
        // Why: error handling must not become a secondary personal-data disclosure path.
        // Covers: privacy-safe malformed input response.
        // Prevents: accidental reflection of passwords, identifiers or sensitive values.

        var privateMarker = "synthetic-private-value-do-not-return";

        var body = """
                {
                  "customerId": "%s",
                  "items": []
                }
                """.formatted(privateMarker);

        mockMvc.perform(post("/orders")
                        .header("X-Tenant-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        not(containsString(privateMarker))));

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsUnsupportedHttpMethod() throws Exception {
        // Why: API operations must expose only explicitly designed semantics.
        // Covers: HTTP method contract.
        // Prevents: accidental resource operations created by configuration drift.

        mockMvc.perform(get("/orders"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        verifyNoInteractions(createOrderUseCase);
    }

    /**
     * Supplies malformed but syntactically valid JSON scenarios so that validation
     * behavior remains explicit without duplicating HTTP setup in every test.
     */
    private static Stream<Arguments> invalidRequestBodies() {

        return Stream.of(
                // Why: an order must always identify its customer.
                Arguments.of(
                        "missing customerId",
                        """
                        {
                          "items": [{
                            "productId": "33333333-3333-3333-3333-333333333333",
                            "quantity": 2
                          }]
                        }
                        """),

                // Why: null collection and empty collection are distinct client errors.
                Arguments.of(
                        "null items",
                        """
                        {
                          "customerId": "22222222-2222-2222-2222-222222222222",
                          "items": null
                        }
                        """),

                // Why: empty orders are invalid before reaching the domain.
                Arguments.of(
                        "empty items",
                        """
                        {
                          "customerId": "22222222-2222-2222-2222-222222222222",
                          "items": []
                        }
                        """),

                // Why: null collection elements otherwise cause delayed runtime failures.
                Arguments.of(
                        "null item",
                        """
                        {
                          "customerId": "22222222-2222-2222-2222-222222222222",
                          "items": [null]
                        }
                        """),

                // Why: every order item must reference a concrete product.
                Arguments.of(
                        "missing productId",
                        """
                        {
                          "customerId": "22222222-2222-2222-2222-222222222222",
                          "items": [{
                            "quantity": 2
                          }]
                        }
                        """),

                // Why: absent quantity must not silently become zero.
                Arguments.of(
                        "missing quantity",
                        """
                        {
                          "customerId": "22222222-2222-2222-2222-222222222222",
                          "items": [{
                            "productId": "33333333-3333-3333-3333-333333333333"
                          }]
                        }
                        """),

                // Why: zero quantity has no valid ordering semantics.
                Arguments.of(
                        "zero quantity",
                        """
                        {
                          "customerId": "22222222-2222-2222-2222-222222222222",
                          "items": [{
                            "productId": "33333333-3333-3333-3333-333333333333",
                            "quantity": 0
                          }]
                        }
                        """),

                // Why: negative quantities could otherwise corrupt future stock calculations.
                Arguments.of(
                        "negative quantity",
                        """
                        {
                          "customerId": "22222222-2222-2222-2222-222222222222",
                          "items": [{
                            "productId": "33333333-3333-3333-3333-333333333333",
                            "quantity": -1
                          }]
                        }
                        """));
    }

    /**
     * Produces the canonical valid payload used by tests that vary request metadata
     * instead of the JSON structure.
     */
    private static String validBody(
            UUID customerId,
            UUID productId) {

        return """
                {
                  "customerId": "%s",
                  "items": [
                    {
                      "productId": "%s",
                      "quantity": 2
                    }
                  ]
                }
                """.formatted(customerId, productId);
    }
}