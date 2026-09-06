package io.github.piresrenan.orderhub.organizations.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationRepository;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql.PostgreSqlOrganizationTenantPlacementRepository;
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
    OrganizationTenantPlacementRepository organizationTenantPlacementRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        return new PostgreSqlOrganizationTenantPlacementRepository(
                jdbcTemplate,
                transactionManager);
    }
}
