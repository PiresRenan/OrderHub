package io.github.piresrenan.orderhub.orders.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(OrderController.class)
@Import(TrustedTenantMvcTestConfiguration.class)
class OrderHttpResourceLimitsTest {

        private static final int EXPECTED_MAX_ITEMS = 1_000;
        private static final long EXPECTED_MAX_DOCUMENT_LENGTH = 1_048_576L;
        private static final int EXPECTED_MAX_NESTING_DEPTH = 64;
        private static final long EXPECTED_MAX_TOKEN_COUNT = 100_000L;

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JsonMapper jsonMapper;

        @MockitoBean
        private CreateOrderUseCase createOrderUseCase;

        @MockitoBean
        private ResolveTrustedTenantContextUseCase trustedTenants;

        @BeforeEach
        void allowsRequestedTenantForResourceLimitSlice() {
                // Why: resource-limit tests exercise Orders after the authenticated
                // Tenant trust boundary, not JWT or membership behavior.
                // Covers: production TrustedTenantContextArgumentResolver with a
                // deterministic successful Tenant-resolution fixture.
                // Prevents: MVC falling back to model-attribute construction of
                // TrustedTenantContext before parser/resource-limit behavior runs.

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
        void configuresExplicitJsonResourceLimits() {
                // Why: parser resource limits must not depend silently on library defaults.
                // Covers: application-level Jackson document, nesting and token constraints.
                // Prevents: framework upgrades silently widening the accepted attack surface.

                var constraints = jsonMapper
                                .tokenStreamFactory()
                                .streamReadConstraints();

                assertThat(constraints.getMaxDocumentLength())
                                .isEqualTo(EXPECTED_MAX_DOCUMENT_LENGTH);

                assertThat(constraints.getMaxNestingDepth())
                                .isEqualTo(EXPECTED_MAX_NESTING_DEPTH);

                assertThat(constraints.getMaxTokenCount())
                                .isEqualTo(EXPECTED_MAX_TOKEN_COUNT);
        }

        @Test
        void rejectsJsonBeyondConfiguredNestingDepth() {
                // Why: deeply nested JSON can consume disproportionate parser stack and CPU.
                // Covers: the active Jackson nesting-depth constraint.
                // Prevents: parser resource-exhaustion payloads with structurally excessive
                // nesting before they can reach application logic.

                var nestedJson = nestedArray(EXPECTED_MAX_NESTING_DEPTH + 1);

                assertThatThrownBy(() -> jsonMapper.readTree(nestedJson))
                                .isInstanceOf(StreamConstraintsException.class);
        }

        @Test
        void rejectsJsonDocumentBeyondConfiguredLength() throws Exception {
                // Why: syntactically valid JSON can still be unreasonably large.
                // Covers: parser-level maximum JSON document length.
                // Prevents: oversized bodies consuming unbounded parser memory and CPU.
                //
                // Leading whitespace keeps the business payload itself valid, ensuring this
                // test fails because of document size rather than another validation rule.

                stubSuccessfulOrder();

                var oversizedBody = " ".repeat((int) EXPECTED_MAX_DOCUMENT_LENGTH + 1)
                                + validBody();

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(oversizedBody))
                                .andExpect(status().isBadRequest())
                                .andExpect(content().contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

                verifyNoInteractions(createOrderUseCase);
        }

        @Test
        void rejectsOrderBeyondTechnicalItemLimit() throws Exception {
                // Why: a valid JSON document can contain a collection large enough to cause
                // excessive mapping, allocation and downstream processing.
                // Covers: the Orders-specific technical item-count safety limit.
                // Prevents: a single request creating unbounded application work even when
                // the payload remains syntactically and structurally valid.

                stubSuccessfulOrder();

                var body = bodyWithItems(EXPECTED_MAX_ITEMS + 1);

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isPayloadTooLarge())
                                .andExpect(content().contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"))
                                .andExpect(jsonPath("$.title").value("Request too large"))
                                .andExpect(jsonPath("$.detail").value("The request exceeds the supported processing limits."));
                ;

                verifyNoInteractions(createOrderUseCase);
        }

        @Test
        void acceptsOrderAtTechnicalItemLimit() throws Exception {
                // Why: the configured technical threshold itself must remain accepted.
                // Covers: the inclusive upper boundary of the item-count protection.
                // Prevents: off-by-one implementations that reject the documented maximum.

                stubSuccessfulOrder();

                var body = bodyWithItems(EXPECTED_MAX_ITEMS);

                mockMvc.perform(authenticatedPost()
                                .header("X-Tenant-Id", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isCreated());

        }

        @Test
        void rejectsInvalidConfiguredItemLimit() {
                // Why: an invalid operational limit must fail during application composition
                // instead of silently disabling or corrupting the protection boundary.
                // Covers: fail-fast validation of the Orders HTTP max-items configuration.
                // Prevents: deployments starting with zero or negative request limits.

                assertThatThrownBy(() -> new OrderController(createOrderUseCase, 0))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage(
                                                "orderhub.orders.http.max-items must be greater than zero");
        }

        @Test
        void rejectsJsonBeyondConfiguredTokenCount() {
                // Why: shallow JSON can still force excessive parser work through a very
                // large number of otherwise inexpensive tokens.
                // Covers: the active Jackson token-count constraint.
                // Prevents: parser CPU and allocation abuse that bypasses nesting limits.

                var json = IntStream.range(
                                0,
                                Math.toIntExact(EXPECTED_MAX_TOKEN_COUNT))
                                .mapToObj(index -> "0")
                                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

                assertThatThrownBy(() -> jsonMapper.readTree(json))
                                .isInstanceOf(StreamConstraintsException.class);
        }

        /**
         * Creates an Orders request positioned immediately after OrderHub's
         * authentication boundary.
         *
         * <p>JWT-to-principal behavior is covered by Security integration tests.
         * This focused resource-limit slice supplies only the minimized internal
         * authenticated principal required by the production Tenant resolver.
         *
         * @return authenticated POST request for the Orders endpoint
         */
        private static MockHttpServletRequestBuilder authenticatedPost() {

                return post("/orders")
                                .principal(
                                                new AuthenticatedUserAuthenticationToken(
                                                                new AuthenticatedUserPrincipal(
                                                                                UUID.fromString(
                                                                                                "11111111-1111-1111-1111-111111111111"))));
        }
        /**
         * Creates a valid application response so an accidentally accepted oversized
         * request produces a successful response instead of failing because of an
         * unstubbed mock.
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
         * Generates a JSON array nested to the requested depth without introducing
         * application-specific fields that could fail for unrelated reasons.
         */
        private static String nestedArray(int depth) {

                return "[".repeat(depth)
                                + "0"
                                + "]".repeat(depth);
        }

        /**
         * Builds a syntactically valid order containing the requested number of items.
         */
        private static String bodyWithItems(int itemCount) {

                var items = IntStream.range(0, itemCount)
                                .mapToObj(index -> """
                                                {
                                                  "variantId": "33333333-3333-3333-3333-333333333333",
                                                  "quantity": 1
                                                }
                                                """)
                                .toList();

                return """
                                {
                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                  "items": [
                                    %s
                                  ]
                                }
                                """.formatted(String.join(",", items));
        }

        /**
         * Produces a minimal valid order body for parser-size tests.
         */
        private static String validBody() {

                return """
                                {
                                  "customerId": "22222222-2222-2222-2222-222222222222",
                                  "items": [{
                                    "variantId": "33333333-3333-3333-3333-333333333333",
                                    "quantity": 1
                                  }]
                                }
                                """;
        }
}