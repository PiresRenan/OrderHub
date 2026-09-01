package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.example.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-orders-e2e-jwks"
})
@AutoConfigureMockMvc
@Import({
        PostgreSqlTestConfiguration.class,
        SecurityRealJwtOrdersHttpEndToEndTest.RealJwtConfiguration.class
})
class SecurityRealJwtOrdersHttpEndToEndTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String AUDIENCE =
            "orderhub-api";

    private static final String SUBJECT =
            "synthetic-orders-e2e-subject";

    private static final RSAKey SIGNING_KEY =
            generateRsaKey(
                    "orderhub-orders-e2e-key");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private FindTenantMembershipUseCase memberships;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @Test
    void createsOrderOnlyAfterRealJwtAuthenticationAndTenantMembership()
            throws Exception {

        // Why: the complete HTTP path must derive Order Tenant authority from a
        // cryptographically authenticated User plus verified Tenant membership.
        // Covers: real JWT -> internal User -> membership -> TrustedTenantContext
        // -> OrderController -> CreateOrderCommand.
        // Prevents: raw X-Tenant-Id values becoming authoritative inside Orders.

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        allowMembership(
                userId,
                tenantId);

        when(createOrderUseCase.create(
                any(CreateOrderCommand.class)))
                .thenAnswer(invocation -> {
                    var command =
                            invocation.getArgument(
                                    0,
                                    CreateOrderCommand.class);

                    var order =
                            Order.create(
                                    UUID.randomUUID(),
                                    command.tenantId(),
                                    command.customerId(),
                                    command.items()
                                            .stream()
                                            .map(item -> new OrderItem(
                                                    item.variantId(),
                                                    item.quantity()))
                                            .toList());

                    return new CreateOrderResult(
                            order,
                            CreateOrderAllocationOutcome.FULLY_ALLOCATED);
                });

        mockMvc.perform(
                        post("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken()))
                                .header(
                                        "X-Tenant-Id",
                                        tenantId)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody(
                                                customerId,
                                                variantId)))
                .andExpect(
                        status().isCreated())
                .andExpect(
                        jsonPath("$.tenantId")
                                .value(
                                        tenantId.toString()))
                .andExpect(
                        jsonPath("$.customerId")
                                .value(
                                        customerId.toString()));

        verify(externalIdentities)
                .resolve(
                        new ResolveExternalIdentityQuery(
                                ISSUER,
                                SUBJECT));

        verify(memberships)
                .find(
                        new FindTenantMembershipQuery(
                                userId,
                                tenantId));

        var commandCaptor =
                ArgumentCaptor.forClass(
                        CreateOrderCommand.class);

        verify(createOrderUseCase)
                .create(
                        commandCaptor.capture());

        assertThat(
                commandCaptor
                        .getValue()
                        .tenantId())
                .isEqualTo(
                        tenantId);
    }

    @Test
    void deniesAuthenticatedUserWithoutRequestedTenantMembership(
            CapturedOutput output)
            throws Exception {

        // Why: a valid signed bearer does not authorize an arbitrary Tenant.
        // Covers: successful JWT authentication followed by failed membership
        // verification.
        // Prevents: caller-controlled Tenant selectors crossing boundaries.

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        denyMembership(
                userId,
                tenantId);

        var token =
                validToken();

        var response =
                mockMvc.perform(
                                post("/orders")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(
                                                        token))
                                        .header(
                                                "X-Tenant-Id",
                                                tenantId)
                                        .contentType(
                                                MediaType.APPLICATION_JSON)
                                        .content(
                                                validBody(
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID())))
                        .andExpect(
                                status().isForbidden())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response)
                .doesNotContain(
                        userId.toString(),
                        tenantId.toString(),
                        ISSUER,
                        SUBJECT,
                        token);

        assertThat(output.getAll())
                .doesNotContain(
                        token,
                        ISSUER,
                        SUBJECT,
                        userId.toString(),
                        tenantId.toString());

        verify(createOrderUseCase,
                never())
                .create(
                        any());
    }

    @Test
    void rejectsMissingTenantSelectorAfterRealJwtAuthentication()
            throws Exception {

        // Why: Tenant selection remains mandatory even after successful bearer
        // authentication.
        // Covers: real authentication followed by missing HTTP Tenant selector.
        // Prevents: unscoped Orders operations.

        var userId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        mockMvc.perform(
                        post("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken()))
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody(
                                                UUID.randomUUID(),
                                                UUID.randomUUID())))
                .andExpect(
                        status().isBadRequest());

        verifyNoInteractions(
                memberships,
                createOrderUseCase);
    }

    @Test
    void rejectsMalformedTenantSelectorAfterRealJwtAuthentication()
            throws Exception {

        // Why: malformed Tenant metadata is a request error, not authorization.
        // Covers: real authentication followed by non-UUID X-Tenant-Id.
        // Prevents: malformed selectors reaching membership or Orders logic.

        var userId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        mockMvc.perform(
                        post("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken()))
                                .header(
                                        "X-Tenant-Id",
                                        "not-a-uuid")
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody(
                                                UUID.randomUUID(),
                                                UUID.randomUUID())))
                .andExpect(
                        status().isBadRequest());

        verifyNoInteractions(
                memberships,
                createOrderUseCase);
    }

    @Test
    void changingOnlyTenantSelectorCannotCrossTenantBoundary()
            throws Exception {

        // Why: Tenant authorization must be recomputed for each request selector.
        // Covers: same signed token and same internal User against one allowed and
        // one denied Tenant.
        // Prevents: reusing authentication as blanket multi-Tenant authority.

        var userId =
                UUID.randomUUID();

        var allowedTenantId =
                UUID.randomUUID();

        var deniedTenantId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        allowMembership(
                userId,
                allowedTenantId);

        denyMembership(
                userId,
                deniedTenantId);

        when(createOrderUseCase.create(
                any(CreateOrderCommand.class)))
                .thenAnswer(invocation -> {
                    var command =
                            invocation.getArgument(
                                    0,
                                    CreateOrderCommand.class);

                    var order =
                            Order.create(
                                    UUID.randomUUID(),
                                    command.tenantId(),
                                    command.customerId(),
                                    List.of(
                                            new OrderItem(
                                                    UUID.randomUUID(),
                                                    1)));

                    return new CreateOrderResult(
                            order,
                            CreateOrderAllocationOutcome.FULLY_ALLOCATED);
                });

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var token =
                validToken();

        mockMvc.perform(
                        post("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token))
                                .header(
                                        "X-Tenant-Id",
                                        allowedTenantId)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody(
                                                customerId,
                                                variantId)))
                .andExpect(
                        status().isCreated());

        mockMvc.perform(
                        post("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token))
                                .header(
                                        "X-Tenant-Id",
                                        deniedTenantId)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        validBody(
                                                customerId,
                                                variantId)))
                .andExpect(
                        status().isForbidden());

        verify(memberships)
                .find(
                        new FindTenantMembershipQuery(
                                userId,
                                allowedTenantId));

        verify(memberships)
                .find(
                        new FindTenantMembershipQuery(
                                userId,
                                deniedTenantId));

        verify(createOrderUseCase)
                .create(
                        any(CreateOrderCommand.class));
    }

    private void allowIdentity(
            UUID userId) {

        when(externalIdentities.resolve(
                new ResolveExternalIdentityQuery(
                        ISSUER,
                        SUBJECT)))
                .thenReturn(
                        Optional.of(
                                new ResolvedUserIdentity(
                                        userId)));
    }

    private void allowMembership(
            UUID userId,
            UUID tenantId) {

        var membership =
                mock(
                        TenantMembership.class);

        doReturn(
                Optional.of(
                        membership))
                .when(
                        memberships)
                .find(
                        new FindTenantMembershipQuery(
                                userId,
                                tenantId));
    }

    private void denyMembership(
            UUID userId,
            UUID tenantId) {

        doReturn(
                Optional.empty())
                .when(
                        memberships)
                .find(
                        new FindTenantMembershipQuery(
                                userId,
                                tenantId));
    }

    private static String validToken()
            throws JOSEException {

        var now =
                Instant.now();

        return RealJwtTestSupport.signedToken(
                SIGNING_KEY,
                ISSUER,
                SUBJECT,
                AUDIENCE,
                now.plusSeconds(
                        300),
                now.minusSeconds(
                        30));
    }
    private static String bearer(
            String token) {

        return "Bearer " + token;
    }

    private static String validBody(
            UUID customerId,
            UUID variantId) {

        return """
                {
                  "customerId": "%s",
                  "items": [{
                    "variantId": "%s",
                    "quantity": 2
                  }]
                }
                """.formatted(
                customerId,
                variantId);
    }

    private static RSAKey generateRsaKey(
            String keyId) {

        return RealJwtTestSupport.generateRsaKey(
                keyId);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class RealJwtConfiguration {

        /**
         * Supplies the real JWT decoder for the complete Orders HTTP vertical.
         *
         * <p>Only remote JWK discovery is substituted. Signature verification,
         * temporal validation, issuer validation and audience validation remain
         * production behavior.
         *
         * @return real Nimbus decoder backed by the synthetic public key
         * @throws JOSEException when the RSA public key cannot be materialized
         */
        @Bean
        @Primary
        JwtDecoder realJwtDecoder()
                throws JOSEException {

            return RealJwtTestSupport.decoder(
                    SIGNING_KEY,
                    ISSUER,
                    AUDIENCE);
        }
    }
}
