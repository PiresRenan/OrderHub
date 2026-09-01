package io.github.piresrenan.orderhub.orders.config;

import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlOrderRepository;
import io.github.piresrenan.orderhub.orders.adapter.out.transaction.spring.SpringTransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.service.CreateOrderService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        OrderTransactionProperties.class)
public class OrdersConfiguration {

    @Bean
    OrderRepository orderRepository(
            JdbcTemplate jdbcTemplate) {

        return new PostgreSqlOrderRepository(
                jdbcTemplate);
    }

    /**
     * Creates the infrastructure transaction boundary used by Order creation.
     *
     * <p>
     * The finite timeout is externalized and propagated to Spring's JDBC
     * transaction resources. A blocked PostgreSQL statement therefore cannot
     * wait indefinitely for another transaction to release a row lock.
     * </p>
     */
    @Bean
    TransactionExecutor transactionExecutor(
            PlatformTransactionManager transactionManager,
            OrderTransactionProperties properties) {

        var transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        transactionTemplate.setTimeout(
                properties.timeoutSeconds());

        return new SpringTransactionExecutor(
                transactionTemplate);
    }

    @Bean
    OrderIdGenerator orderIdGenerator() {

        return UUID::randomUUID;
    }

    @Bean
    CreateOrderUseCase createOrderUseCase(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator,
            TransactionExecutor transactionExecutor,
            CommitOrderInventoryUseCase inventory) {

        return new CreateOrderService(
                orderRepository,
                orderIdGenerator,
                transactionExecutor,
                inventory);
    }
}