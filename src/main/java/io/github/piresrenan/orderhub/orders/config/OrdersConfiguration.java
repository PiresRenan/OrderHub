package io.github.piresrenan.orderhub.orders.config;

import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedCreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedTransactionExecutor;
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
     * Creates the application-owned transaction boundary and instruments its
     * exact execution duration without business-identity metric tags.
     */
    @Bean
    TransactionExecutor transactionExecutor(
            PlatformTransactionManager transactionManager,
            OrderTransactionProperties properties,
            MeterRegistry meterRegistry) {

        var transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        transactionTemplate.setTimeout(
                properties.timeoutSeconds());

        var springTransactionExecutor =
                new SpringTransactionExecutor(
                        transactionTemplate);

        return new MicrometerObservedTransactionExecutor(
                springTransactionExecutor,
                meterRegistry);
    }

    @Bean
    OrderIdGenerator orderIdGenerator() {

        return UUID::randomUUID;
    }

    /**
     * Exposes the create-Order use case through a low-cardinality observability
     * decorator while preserving the Order-owned transaction boundary.
     */
    @Bean
    CreateOrderUseCase createOrderUseCase(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator,
            TransactionExecutor transactionExecutor,
            ValidateOrderableVariantsUseCase catalog,
            CommitOrderInventoryUseCase inventory,
            MeterRegistry meterRegistry) {

        var createOrderService =
                new CreateOrderService(
                        orderRepository,
                        orderIdGenerator,
                        transactionExecutor,
                        catalog,
                        inventory);

        return new MicrometerObservedCreateOrderUseCase(
                createOrderService,
                meterRegistry);
    }
}
