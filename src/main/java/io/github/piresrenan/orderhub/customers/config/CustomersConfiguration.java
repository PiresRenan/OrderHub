package io.github.piresrenan.orderhub.customers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerAccountLinkingUseCase;
import io.github.piresrenan.orderhub.customers.application.service.CustomerAccountLinkingService;
import io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql.PostgreSqlCustomerLinkProofRepository;
import io.github.piresrenan.orderhub.customers.adapter.out.transaction.spring.SpringCustomerLinkTransactionExecutor;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.EnsureActiveTenantMembershipUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;

import io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql.PostgreSqlCustomerAccountBindingRepository;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingRepository;
import io.github.piresrenan.orderhub.customers.application.service.ResolveCustomerAccountBindingService;

@Configuration(proxyBeanMethods = false)
public class CustomersConfiguration {

    @Bean
    CustomerAccountLinkingUseCase customerAccountLinkingUseCase(
            JdbcTemplate jdbc, PlatformTransactionManager manager, AuthorizeStaffTenantActionUseCase authority,
            IsTenantMembershipOperationallyActiveUseCase memberships, EnsureActiveTenantMembershipUseCase ensureMembership,
            FindTenantOperationalStateUseCase tenants) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new CustomerAccountLinkingService(
                new PostgreSqlCustomerLinkProofRepository(jdbc),
                new SpringCustomerLinkTransactionExecutor(transaction),
                authority, memberships, ensureMembership, tenants);
    }

    @Bean
    CustomerAccountBindingRepository customerAccountBindingRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlCustomerAccountBindingRepository(
                jdbcTemplate);
    }

    @Bean
    ResolveCustomerAccountBindingUseCase resolveCustomerAccountBindingUseCase(
            CustomerAccountBindingRepository repository) {

        return new ResolveCustomerAccountBindingService(
                repository);
    }
}
