package io.github.piresrenan.orderhub.authorization.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.service.CustomerOwnedResourceAuthorizationService;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    @Bean
    AuthorizeCustomerOwnedResourceActionUseCase authorizeCustomerOwnedResourceActionUseCase() {

        return new CustomerOwnedResourceAuthorizationService();
    }
}
