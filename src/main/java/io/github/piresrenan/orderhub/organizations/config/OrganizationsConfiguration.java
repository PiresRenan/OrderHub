package io.github.piresrenan.orderhub.organizations.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationLifecycleRepository;
import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationRepository;
import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationTenantPlacementRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementRepository;

@Configuration(proxyBeanMethods = false)
public class OrganizationsConfiguration {

    @Bean
    OrganizationRepository organizationRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlOrganizationRepository(
                jdbcTemplate);
    }

    @Bean
    OrganizationLifecycleRepository organizationLifecycleRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        return new PostgreSqlOrganizationLifecycleRepository(
                jdbcTemplate,
                transactionManager);
    }

    @Bean
    OrganizationTenantPlacementRepository organizationTenantPlacementRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        return new PostgreSqlOrganizationTenantPlacementRepository(
                jdbcTemplate,
                transactionManager);
    }
}
