package io.github.piresrenan.orderhub.authorization.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlAdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.service.AdministrativeAuthorizationService;
import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.service.CustomerOwnedResourceAuthorizationService;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    @Bean
    AuthorizeCustomerOwnedResourceActionUseCase authorizeCustomerOwnedResourceActionUseCase() {

        return new CustomerOwnedResourceAuthorizationService();
    }
    @Bean
    AdministrativeGrantRepository administrativeGrantRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlAdministrativeGrantRepository(
                jdbcTemplate);
    }
    @Bean
    AuthorizeAdministrativeActionUseCase authorizeAdministrativeActionUseCase(
            AdministrativeGrantRepository grants) {

        return new AdministrativeAuthorizationService(
                grants);
    }
}
