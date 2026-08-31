package io.github.piresrenan.orderhub.security.support;

import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.piresrenan.orderhub.security.adapter.in.web.TrustedTenantContextArgumentResolver;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;

/**
 * Installs the production trusted Tenant argument resolver in focused MVC
 * slices without loading the complete Resource Server configuration.
 *
 * <p>Authentication and Tenant authorization inputs remain explicit fixtures
 * owned by each test. This configuration only reproduces the production MVC
 * registration mechanism.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TrustedTenantMvcTestConfiguration {

    /**
     * Creates the production resolver against the test-supplied Security
     * application boundary.
     *
     * @param trustedTenants mocked trusted Tenant resolution boundary
     * @return production trusted Tenant MVC resolver
     */
    @Bean
    TrustedTenantContextArgumentResolver trustedTenantContextArgumentResolver(
            ResolveTrustedTenantContextUseCase trustedTenants) {

        return new TrustedTenantContextArgumentResolver(
                trustedTenants);
    }

    /**
     * Registers the production resolver with the MVC slice.
     *
     * @param tenantResolver trusted Tenant resolver
     * @return MVC test customization
     */
    @Bean
    WebMvcConfigurer trustedTenantMvcConfigurer(
            TrustedTenantContextArgumentResolver tenantResolver) {

        return new WebMvcConfigurer() {

            @Override
            public void addArgumentResolvers(
                    List<HandlerMethodArgumentResolver> resolvers) {

                resolvers.add(
                        tenantResolver);
            }
        };
    }
}
