package io.github.piresrenan.orderhub.authorization.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlAdministrativeGrantAuditRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql.PostgreSqlAdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.adapter.out.transaction.spring.SpringAuthorizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantAuditRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationTransactionExecutor;
import io.github.piresrenan.orderhub.authorization.application.service.AdministrativeAuthorizationService;
import io.github.piresrenan.orderhub.authorization.application.service.AuditedAdministrativeGrantMutationService;
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
    AdministrativeGrantAuditRepository administrativeGrantAuditRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlAdministrativeGrantAuditRepository(
                jdbcTemplate);
    }

    @Bean
    AuthorizationTransactionExecutor authorizationTransactionExecutor(
            PlatformTransactionManager transactionManager) {

        return new SpringAuthorizationTransactionExecutor(
                transactionManager);
    }

    @Bean
    AuditedAdministrativeGrantMutationService auditedAdministrativeGrantMutationService(
            AuthorizationTransactionExecutor transactions,
            AdministrativeGrantRepository grants,
            AdministrativeGrantAuditRepository audit) {

        return new AuditedAdministrativeGrantMutationService(
                transactions,
                grants,
                audit,
                UUID::randomUUID);
    }

    @Bean
    AuthorizeAdministrativeActionUseCase authorizeAdministrativeActionUseCase(
            AdministrativeGrantRepository grants) {

        return new AdministrativeAuthorizationService(
                grants);
    }
}
