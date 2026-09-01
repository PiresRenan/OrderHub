package io.github.piresrenan.orderhub.orders.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlOrderRepository;
import io.github.piresrenan.orderhub.orders.adapter.out.transaction.spring.SpringTransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.service.CreateOrderService;

@Configuration(proxyBeanMethods = false)
public class OrdersConfiguration {

    /**
     * Provides relational Order persistence without owning transaction
     * demarcation.
     */
    @Bean
    OrderRepository orderRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlOrderRepository(
                jdbcTemplate);
    }

    /**
     * Adapts Spring transaction infrastructure to the framework-neutral
     * application transaction port.
     */
    @Bean
    TransactionExecutor transactionExecutor(
            PlatformTransactionManager transactionManager) {

        return new SpringTransactionExecutor(
                new TransactionTemplate(
                        transactionManager));
    }

    @Bean
    OrderIdGenerator orderIdGenerator() {

        return UUID::randomUUID;
    }

    /**
     * Composes the Create Order use case with caller-owned transaction
     * demarcation.
     */
    @Bean
    CreateOrderUseCase createOrderUseCase(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator,
            TransactionExecutor transactionExecutor) {

        return new CreateOrderService(
                orderRepository,
                orderIdGenerator,
                transactionExecutor);
    }
}