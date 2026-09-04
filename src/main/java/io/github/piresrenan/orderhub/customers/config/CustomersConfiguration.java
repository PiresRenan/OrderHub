package io.github.piresrenan.orderhub.customers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql.PostgreSqlCustomerAccountBindingRepository;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingRepository;
import io.github.piresrenan.orderhub.customers.application.service.ResolveCustomerAccountBindingService;

@Configuration(proxyBeanMethods = false)
public class CustomersConfiguration {

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
