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
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;

/**
 * Spring composition root for the Security module.
 *
 * <p>Security infrastructure is wired here so application models and use cases
 * remain independent of Spring Security and configuration-binding concerns.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        JwtResourceServerProperties.class)
public class SecurityConfiguration {

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
     * Composes trusted Tenant-context derivation from the Users-owned membership
     * lookup boundary.
     *
     * <p>Security consumes only the Users application input contract. Membership
     * presence is interpreted by the Security application service and Users
     * domain or persistence types are not exposed across this module boundary.
     *
     * @param memberships Users-owned Tenant membership lookup boundary
     * @return trusted Tenant-context resolution use case
     */
    @Bean
    ResolveTrustedTenantContextUseCase resolveTrustedTenantContextUseCase(
            FindTenantMembershipUseCase memberships) {

        return new ResolveTrustedTenantContextService(
                memberships);
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
            JwtResourceServerProperties properties) {

        var decoder =
                NimbusJwtDecoder
                        .withJwkSetUri(
                                properties.jwkSetUri())
                        .build();

        decoder.setJwtValidator(
                new JwtValidationPolicy(
                        properties.issuer(),
                        properties.audience()));

        return decoder;
    }
}
