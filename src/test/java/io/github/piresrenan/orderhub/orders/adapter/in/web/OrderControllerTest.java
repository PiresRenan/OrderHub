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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
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
class OrderControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private CreateOrderUseCase createOrderUseCase;

        @MockitoBean
        private ResolveTrustedTenantContextUseCase trustedTenants;

        @BeforeEach
        void allowsRequestedTenantForControllerSlice() {
                // Why: this MVC slice exercises Orders after the Security trust
                // boundary, while the Security HTTP tests independently verify
                // authorization success and denial.
                // Covers: production TrustedTenantContextArgumentResolver with a
                // deterministic allowed Tenant fixture.
                // Prevents: falling back to MVC model-attribute construction of
                // TrustedTenantContext.

                when(trustedTenants.resolve(
                                any(ResolveTrustedTenantContextQuery.class)))
                                .thenAnswer(invocation -> {
                                        var query = invocation.getArgument(
                                                        0,
                                                        ResolveTrustedTenantContextQuery.class);

                                        return Optional.of(
                                                        new TrustedTenantContext(
                                                                        query.requestedTenantId()));
                                });
        }

        @Test
        void createsOrderFromValidRequest() throws Exception {
                // Why: establishes the canonical successful HTTP contract.
                // Covers: JSON binding, header mapping, command mapping and response
                // serialization.
                // Prevents: silent contract drift between API clients and the application
                // layer.

                var orderId = UUID.randomUUID();
                var tenantId = UUID.randomUUID();
                var customerId = UUID.randomUUID();
                var variantId = UUID.randomUUID();

                var order = Order.create(
                                orderId,
                                tenantId,
                                customerId,
                                List.of(new OrderItem(variantId, 2)));

                when(createOrderUseCase.create(any(CreateOrderCommand.class)))
                                .thenReturn(
                                                new CreateOrderResult(
                                                                order,
                                                                CreateOrderAllocationOutcome.FULLY_ALLOCATED));

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", tenantId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody(customerId, variantId)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").value(orderId.toString()))
                                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                                .andExpect(jsonPath("$.status").value("CREATED"))
                                .andExpect(jsonPath("$.allocationOutcome").value("FULLY_ALLOCATED"))
                                .andExpect(jsonPath("$.items[0].variantId")
                                                .value(variantId.toString()))
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

                mockMvc.perform(authenticatedPost()
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

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", "not-a-uuid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody(UUID.randomUUID(), UUID.randomUUID())))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));

                verifyNoInteractions(createOrderUseCase);
        }

        @Test
        void rejectsMissingBody() throws Exception {
                // Why: empty requests can originate from broken clients, proxies or
                // integrations.
                // Covers: mandatory JSON body handling.
                // Prevents: null access and ambiguous application failures.

                mockMvc.perform(authenticatedPost()
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
                // Prevents: parser internals leaking to clients or business code receiving
                // garbage.

                mockMvc.perform(authenticatedPost()
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

                mockMvc.perform(authenticatedPost()
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

                mockMvc.perform(authenticatedPost()
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
                var variantId = UUID.randomUUID();

                var body = """
                                {
                                  "customerId": "%s",
                                  "unexpectedField": "ignored-by-loose-parsers",
                                  "items": [
                                    {
                                      "variantId": "%s",
                                      "quantity": 2
                                    }
                                  ]
                                }
                                """.formatted(customerId, variantId);

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

                verifyNoInteractions(createOrderUseCase);
        }

        @Test
        void doesNotEchoRejectedValuesInErrorResponses() throws Exception {
                // Why: error handling must not become a secondary personal-data disclosure
                // path.
                // Covers: privacy-safe malformed input response.
                // Prevents: accidental reflection of passwords, identifiers or sensitive
                // values.

                var privateMarker = "synthetic-private-value-do-not-return";

                var body = """
                                {
                                  "customerId": "%s",
                                  "items": []
                                }
                                """.formatted(privateMarker);

                mockMvc.perform(authenticatedPost()
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

        @ParameterizedTest(name = "{0}")
        @MethodSource("nonDeserializableRequestBodies")
        void rejectsNonDeserializableRequestBodies(
                        String scenario,
                        String requestBody,
                        String rejectedValueMarker) throws Exception {

                // Why: syntactically valid JSON can still contain values incompatible with
                // the public contract and must fail before application processing.
                // Covers: UUID conversion, scalar-type mismatch, fractional integers and
                // numeric overflow during Jackson deserialization.
                // Prevents: coercion surprises, truncated quantities, implementation leakage
                // and malformed values reaching business logic.

                stubSuccessfulOrder();

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest())
                                .andExpect(content().contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                                .andExpect(content().string(
                                                not(containsString(rejectedValueMarker))));

                verifyNoInteractions(createOrderUseCase);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("duplicateFieldBodies")
        void rejectsDuplicateJsonProperties(
                        String scenario,
                        String requestBody,
                        String rejectedValueMarker) throws Exception {

                // Why: duplicate JSON properties create ambiguous interpretation because
                // different parsers or consumers may choose different occurrences.
                // Covers: duplicate fields at root and nested-object levels.
                // Prevents: parser differential behavior, validation bypass and clients
                // exploiting "first value vs last value" interpretation differences.

                stubSuccessfulOrder();

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isBadRequest())
                                .andExpect(content().contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                                .andExpect(content().string(
                                                not(containsString(rejectedValueMarker))));

                verifyNoInteractions(createOrderUseCase);
        }

        @Test
        void rejectsUnacceptableResponseMediaType() throws Exception {
                // Why: clients must receive deterministic behavior when they explicitly
                // refuse every representation produced by the resource.
                // Covers: HTTP Accept negotiation and 406 handling.
                // Prevents: accidental content negotiation drift and uncontrolled framework
                // error representations.
                //
                // This assertion is intentionally strict during RED. If Spring cannot
                // serialize Problem Details because the caller also rejects that media type,
                // the evidence will be used to refine the 406 contract rather than hiding it.

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_XML)
                                .content(validBody(
                                                UUID.randomUUID(),
                                                UUID.randomUUID())))
                                .andExpect(status().isNotAcceptable())
                                .andExpect(content().contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));

                verifyNoInteractions(createOrderUseCase);
        }

        @Test
        void acceptsApplicationJsonWithCharsetParameter() throws Exception {
                // Why: application/json remains valid when a client supplies a charset
                // media-type parameter commonly added by HTTP libraries.
                // Covers: request Content-Type compatibility.
                // Prevents: unnecessarily rejecting standards-compatible client requests.

                var customerId = UUID.randomUUID();
                var variantId = UUID.randomUUID();

                stubSuccessfulOrder();

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.parseMediaType(
                                                "application/json;charset=UTF-8"))
                                .accept(MediaType.APPLICATION_JSON)
                                .content(validBody(customerId, variantId)))
                                .andExpect(status().isCreated());

                verify(createOrderUseCase).create(any(CreateOrderCommand.class));
        }

        /**
         * Creates an Orders MVC request positioned immediately after the
         * authentication boundary.
         *
         * <p>The Security HTTP integration tests prove JWT-to-principal mapping.
         * This focused controller slice therefore supplies only OrderHub's
         * minimized internal authenticated principal.
         *
         * @return authenticated POST request for the Orders endpoint
         */
        private static MockHttpServletRequestBuilder authenticatedPost() {

                return post("/orders")
                .header(
                        "Idempotency-Key",
                        "existing-orders-contract-test-key")
                                .principal(
                                                new AuthenticatedUserAuthenticationToken(
                                                                new AuthenticatedUserPrincipal(
                                                                                UUID.fromString(
                                                                                                "11111111-1111-1111-1111-111111111111"))));
        }
        /**
         * Supplies syntactically valid JSON whose field values cannot safely be bound
         * to
         * the create-order contract.
         */
        private static Stream<Arguments> nonDeserializableRequestBodies() {

                return Stream.of(
                                Arguments.of(
                                                "malformed customer UUID",
                                                """
                                                                {
                                                                  "customerId": "synthetic-invalid-customer-uuid",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": 2
                                                                  }]
                                                                }
                                                                """,
                                                "synthetic-invalid-customer-uuid"),

                                Arguments.of(
                                                "malformed product UUID",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "synthetic-invalid-product-uuid",
                                                                    "quantity": 2
                                                                  }]
                                                                }
                                                                """,
                                                "synthetic-invalid-product-uuid"),

                                Arguments.of(
                                                "numeric customerId instead of UUID string",
                                                """
                                                                {
                                                                  "customerId": 987654321,
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": 2
                                                                  }]
                                                                }
                                                                """,
                                                "987654321"),

                                Arguments.of(
                                                "object variantId instead of UUID string",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": {
                                                                      "raw": "synthetic-product-object-marker"
                                                                    },
                                                                    "quantity": 2
                                                                  }]
                                                                }
                                                                """,
                                                "synthetic-product-object-marker"),

                                Arguments.of(
                                                "decimal quantity",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": 2.75
                                                                  }]
                                                                }
                                                                """,
                                                "2.75"),

                                Arguments.of(
                                                "quantity above Integer maximum",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": 2147483648
                                                                  }]
                                                                }
                                                                """,
                                                "2147483648"),

                                Arguments.of(
                                                "quantity below Integer minimum",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": -2147483649
                                                                  }]
                                                                }
                                                                """,
                                                "-2147483649"));
        }

        /**
         * Supplies duplicate JSON properties at different contract levels to verify
         * that
         * ambiguous payloads are rejected instead of resolved by parser ordering.
         */
        private static Stream<Arguments> duplicateFieldBodies() {

                return Stream.of(
                                Arguments.of(
                                                "duplicate customerId",
                                                """
                                                                {
                                                                  "customerId": "11111111-1111-1111-1111-111111111111",
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": 2
                                                                  }]
                                                                }
                                                                """,
                                                "22222222-2222-2222-2222-222222222222"),

                                Arguments.of(
                                                "duplicate variantId",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "variantId": "44444444-4444-4444-4444-444444444444",
                                                                    "quantity": 2
                                                                  }]
                                                                }
                                                                """,
                                                "44444444-4444-4444-4444-444444444444"),

                                Arguments.of(
                                                "duplicate quantity",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": 2,
                                                                    "quantity": 999999937
                                                                  }]
                                                                }
                                                                """,
                                                "999999937"));
        }

        /**
         * Supplies malformed but syntactically valid JSON scenarios so that validation
         * behavior remains explicit without duplicating HTTP setup in every test.
         */
        private static Stream<Arguments> invalidRequestBodies() {

                return Stream.of(
                                // Why: explicit null is semantically different from an omitted field
                                // at the JSON boundary even though both violate the contract.
                                Arguments.of(
                                                "null quantity",
                                                """
                                                                {
                                                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": null
                                                                  }]
                                                                }
                                                                """),
                                Arguments.of(
                                                "missing customerId",
                                                """
                                                                {
                                                                  "items": [{
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
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
                                                "missing variantId",
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
                                                                    "variantId": "33333333-3333-3333-3333-333333333333"
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
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
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
                                                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                                                    "quantity": -1
                                                                  }]
                                                                }
                                                                """));
        }

        /**
         * Configures a valid application response so parser tests fail because of the
         * HTTP contract itself rather than because an unstubbed mock returned null.
         */
        private void stubSuccessfulOrder() {

                var order = Order.create(
                                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                                List.of(new OrderItem(
                                                UUID.fromString(
                                                                "dddddddd-dddd-dddd-dddd-dddddddddddd"),
                                                2)));

                when(createOrderUseCase.create(any(CreateOrderCommand.class)))
                                .thenReturn(
                                                new CreateOrderResult(
                                                                order,
                                                                CreateOrderAllocationOutcome.FULLY_ALLOCATED));
        }

        /**
         * Produces the canonical valid payload used by tests that vary request metadata
         * instead of the JSON structure.
         */
        private static String validBody(
                        UUID customerId,
                        UUID variantId) {

                return """
                                {
                                  "customerId": "%s",
                                  "items": [
                                    {
                                      "variantId": "%s",
                                      "quantity": 2
                                    }
                                  ]
                                }
                                """.formatted(customerId, variantId);
        }
}