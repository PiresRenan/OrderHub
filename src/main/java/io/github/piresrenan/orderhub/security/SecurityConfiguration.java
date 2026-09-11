package io.github.piresrenan.orderhub.security;
import java.util.List;


import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.AuthenticatedUserJwtAuthenticationConverter;
import io.github.piresrenan.orderhub.security.adapter.in.web.TrustedTenantContextArgumentResolver;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.JwtResourceServerProperties;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.JwtValidationPolicy;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserUseCase;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.security.application.service.ResolveAuthenticatedUserService;
import io.github.piresrenan.orderhub.security.application.service.ResolveTrustedTenantContextService;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TrustedExternalIdentityProviders;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.AdditionalJwtTrustProperties;
import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.ConfiguredIssuerJwtDecoder;

/**
 * Spring composition root for the Security module.
 *
 * <p>Security infrastructure is wired here so application models and use cases
 * remain independent of Spring Security and configuration-binding concerns.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        {JwtResourceServerProperties.class, AdditionalJwtTrustProperties.class})
public class SecurityConfiguration {

    /** Only these proof-consumption paths accept a verified identity without an internal binding. */
    @Bean
    @org.springframework.core.annotation.Order(1)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain identityBootstrapFilterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
        http.securityMatcher("/identity/bootstrap/staff", "/identity/bootstrap/external-links")
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(login -> login.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401); response.setContentType("application/problem+json");
                    response.setHeader("WWW-Authenticate", "Bearer"); response.setHeader("Cache-Control", "no-store");
                    response.getWriter().write("{\"type\":\"urn:orderhub:problem:bootstrap-authentication-required\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Valid bearer authentication is required\",\"code\":\"bootstrap-authentication-required\"}");
                }).jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(
                        new io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.VerifiedExternalIdentityJwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Composes the Security application boundary that translates one external
     * identity into OrderHub's internal authenticated User principal.
     *
     * <p>Users retains ownership of external identity bindings. Security consumes
     * only the Users application input contract and does not access Users domain
     * or persistence internals.
     *
     * @param externalIdentities Users-owned external identity resolution boundary
     * @return authenticated User resolution use case
     */
    @Bean
    ResolveAuthenticatedUserUseCase resolveAuthenticatedUserUseCase(
            ResolveExternalIdentityUseCase externalIdentities) {

        return new ResolveAuthenticatedUserService(
                externalIdentities);
    }

    /**
     * Composes trusted Tenant-context derivation from exact membership and
     * Tenant-owned operational state.
     *
     * <p>Security consumes only module-owned application contracts. The Tenant
     * aggregate and Tenant persistence internals never cross this boundary.
     *
     * @param memberships Users-owned membership eligibility boundary
     * @param tenantOperationalStates Tenants-owned operational-state boundary
     * @return trusted Tenant-context resolution use case
     */
    @Bean
    ResolveTrustedTenantContextUseCase resolveTrustedTenantContextUseCase(
            IsTenantMembershipOperationallyActiveUseCase memberships,
            FindTenantOperationalStateUseCase tenantOperationalStates) {

        return new ResolveTrustedTenantContextService(
                memberships,
                tenantOperationalStates);
    }
    /**
     * Creates the servlet adapter that derives trusted Tenant context from the
     * authenticated internal User and the caller-provided Tenant selector.
     *
     * <p>The adapter depends only on the Security application boundary. Its
     * registration is servlet-specific so non-web composition tests and other
     * runtimes do not acquire an artificial Spring MVC dependency.
     *
     * @param trustedTenants application boundary that proves Tenant membership
     * @return trusted Tenant MVC argument resolver
     */
    @Bean
    @ConditionalOnWebApplication(
            type = ConditionalOnWebApplication.Type.SERVLET)
    TrustedTenantContextArgumentResolver trustedTenantContextArgumentResolver(
            ResolveTrustedTenantContextUseCase trustedTenants) {

        return new TrustedTenantContextArgumentResolver(
                trustedTenants);
    }

    /**
     * Registers OrderHub's trusted Tenant resolver with Spring MVC while
     * retaining Spring Boot's default MVC configuration.
     *
     * <p>No {@code EnableWebMvc} replacement configuration is introduced. The
     * custom resolver is added alongside Spring MVC's built-in resolvers.
     *
     * @param tenantResolver trusted Tenant argument resolver
     * @return MVC customization that installs the resolver
     */
    @Bean
    @ConditionalOnWebApplication(
            type = ConditionalOnWebApplication.Type.SERVLET)
    WebMvcConfigurer trustedTenantContextWebMvcConfigurer(
            TrustedTenantContextArgumentResolver tenantResolver) {

        return new WebMvcConfigurer() {

            /**
             * Adds the trusted Tenant adapter to Spring MVC's custom argument
             * resolver collection.
             *
             * @param resolvers custom MVC argument resolvers being configured
             */
            @Override
            public void addArgumentResolvers(
                    List<HandlerMethodArgumentResolver> resolvers) {

                resolvers.add(
                        tenantResolver);
            }
        };
    }
    /**
     * Creates the adapter that projects a validated JWT into the internal
     * authenticated User representation used by OrderHub.
     *
     * @param authenticatedUsers application boundary for internal User resolution
     * @return JWT authentication converter
     */
    @Bean
    AuthenticatedUserJwtAuthenticationConverter
            authenticatedUserJwtAuthenticationConverter(
                    ResolveAuthenticatedUserUseCase authenticatedUsers) {

        return new AuthenticatedUserJwtAuthenticationConverter(
                authenticatedUsers);
    }

    /**
     * Establishes the stateless HTTP authentication boundary for the servlet API.
     *
     * <p>The current API authenticates exclusively through bearer credentials.
     * Operational probe paths required by the deployment platform remain public,
     * while every other request requires authentication. Login sessions, request
     * caching, form login, HTTP Basic login and logout endpoints are deliberately
     * absent from this Resource Server boundary.
     *
     * <p>CSRF protection is disabled for this bearer-only stateless API because
     * OH-010 introduces no cookie-authenticated browser session. A future
     * cookie-authenticated interface must establish its own appropriate CSRF
     * policy rather than inheriting this decision.
     *
     * @param http Spring Security servlet configuration builder
     * @param decoder configured JWT decoder
     * @param authenticationConverter validated-JWT to internal-User converter
     * @return configured servlet Security filter chain
     * @throws Exception when Spring Security cannot build the filter chain
     */
    @Bean
    @ConditionalOnWebApplication(
            type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder decoder,
            AuthenticatedUserJwtAuthenticationConverter
                    authenticationConverter)
            throws Exception {

        http
                .csrf(
                        csrf ->
                                csrf.disable())
                .requestCache(
                        requestCache ->
                                requestCache.disable())
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(
                                        SessionCreationPolicy.STATELESS))
                .formLogin(
                        formLogin ->
                                formLogin.disable())
                .httpBasic(
                        httpBasic ->
                                httpBasic.disable())
                .logout(
                        logout ->
                                logout.disable())
                .authorizeHttpRequests(
                        authorization ->
                                authorization
                                        .requestMatchers(
                                                "/livez",
                                                "/readyz",
                                                "/actuator/health")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(
                        resourceServer ->
                                resourceServer.jwt(
                                        jwt ->
                                                jwt
                                                        .decoder(
                                                                decoder)
                                                        .jwtAuthenticationConverter(
                                                                authenticationConverter)));

        return http.build();
    }

    /**
     * Creates the JWT decoder from the explicitly configured trusted JWK Set and
     * applies OrderHub's issuer, audience and temporal validation policy.
     *
     * <p>Using the configured JWK Set URI directly avoids issuer discovery during
     * application startup. Public signing keys are retrieved by the decoder when
     * token verification requires them.
     *
     * @param properties externalized JWT trust configuration
     * @return configured JWT decoder
     */
    @Bean
    JwtDecoder jwtDecoder(
            JwtResourceServerProperties properties, AdditionalJwtTrustProperties additional) {

        var primary = decoder(properties.issuer(), properties.audience(), properties.jwkSetUri());
        if (additional.additionalIssuers().isEmpty()) { return primary; }
        var decoders = new java.util.HashMap<String, JwtDecoder>();
        decoders.put(properties.issuer(), primary);
        for (var provider : additional.additionalIssuers()) {
            if (decoders.containsKey(provider.issuer())) { throw new IllegalArgumentException("JWT trusted issuers must be unique"); }
            decoders.put(provider.issuer(), decoder(provider.issuer(), properties.audience(), provider.jwkSetUri()));
        }
        return new ConfiguredIssuerJwtDecoder(decoders);
    }

    /** Shares the same server-owned issuer set used by JWT verification with Users last-path protection. */
    @Bean
    TrustedExternalIdentityProviders trustedExternalIdentityProviders(JwtResourceServerProperties properties, AdditionalJwtTrustProperties additional) {
        var issuers = new java.util.HashSet<String>();
        issuers.add(properties.issuer());
        additional.additionalIssuers().forEach(provider -> issuers.add(provider.issuer()));
        var configured = java.util.Set.copyOf(issuers);
        return configured::contains;
    }

    /** Uses Nimbus verification and the shared issuer, audience and temporal policy for one configured provider. */
    private JwtDecoder decoder(String issuer, String audience, String jwkSetUri) {

        var decoder =
                NimbusJwtDecoder
                        .withJwkSetUri(
                                jwkSetUri)
                        .build();

        decoder.setJwtValidator(
                new JwtValidationPolicy(
                        issuer,
                        audience));

        return decoder;
    }
}
