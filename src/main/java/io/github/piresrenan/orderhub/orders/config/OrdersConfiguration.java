package io.github.piresrenan.orderhub.orders.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlOrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.service.CreateOrderService;

@Configuration(proxyBeanMethods = false)
public class OrdersConfiguration {

    /**
     * Provides the durable PostgreSQL implementation of the OrderRepository port.
     *
     * <p>
     * JDBC infrastructure and the transaction manager are supplied by Spring
     * Boot. Transaction demarcation remains owned by the PostgreSQL persistence
     * adapter through TransactionOperations rather than by the application
     * service.
     * </p>
     *
     * @param jdbcTemplate       configured JDBC operations for the application
     *                           DataSource
     * @param transactionManager transaction manager associated with the same
     *                           application DataSource
     * @return PostgreSQL-backed repository used for Order persistence
     */
    @Bean
    OrderRepository orderRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {

        return new PostgreSqlOrderRepository(
                jdbcTemplate,
                new TransactionTemplate(transactionManager));
    }

    /**
     * Provides the production identity-generation strategy for new orders.
     *
     * @return generator backed by UUID.randomUUID
     */
    @Bean
    OrderIdGenerator orderIdGenerator() {
        return UUID::randomUUID;
    }

    /**
     * Composes the order creation use case with its required output ports.
     *
     * <p>
     * Keeping framework composition in configuration allows the application
     * service itself to remain independent of Spring annotations.
     * </p>
     *
     * @param orderRepository  configured order persistence port
     * @param orderIdGenerator configured order identity-generation port
     * @return application input port ready to process order creation commands
     */
    @Bean
    CreateOrderUseCase createOrderUseCase(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator) {
        return new CreateOrderService(
                orderRepository,
                orderIdGenerator);
    }
}
