package io.github.piresrenan.orderhub.orders.config;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.github.piresrenan.orderhub.authorization.application.port.in.AuthorizeCustomerOwnedResourceActionUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.customers.application.port.in.ResolveCustomerAccountBindingUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedCreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedCreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedTransactionExecutor;
import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlCreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlOrderRepository;
import io.github.piresrenan.orderhub.orders.adapter.out.transaction.spring.SpringTransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.ViewCustomerOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.service.CreateCustomerOrderService;
import io.github.piresrenan.orderhub.orders.application.service.CreateOrderService;
import io.github.piresrenan.orderhub.orders.application.service.ViewCustomerOrderService;

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
     * Creates the PostgreSQL authority used only inside the application-owned
     * create-Order transaction.
     *
     * <p>
     * The acquisition timeout is deliberately distinct from the total Order
     * transaction timeout so later Catalog/Inventory lock waits are not
     * misclassified as idempotency contention.
     * </p>
     */
    @Bean
    CreateOrderIdempotencyRepository createOrderIdempotencyRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${orderhub.orders.idempotency.acquisition-timeout}")
            Duration acquisitionTimeout,
            MeterRegistry meterRegistry) {

        var postgreSqlRepository =
                new PostgreSqlCreateOrderIdempotencyRepository(
                        jdbcTemplate,
                        acquisitionTimeout);

        return new MicrometerObservedCreateOrderIdempotencyRepository(
                postgreSqlRepository,
                meterRegistry);
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
            CreateOrderIdempotencyRepository idempotency,
            MeterRegistry meterRegistry) {

        var createOrderService =
                new CreateOrderService(
                        orderRepository,
                        orderIdGenerator,
                        transactionExecutor,
                        catalog,
                        inventory,
                        idempotency);

        return new MicrometerObservedCreateOrderUseCase(
                createOrderService,
                meterRegistry);
    }

    @Bean
    CreateCustomerOrderUseCase createCustomerOrderUseCase(
            ResolveCustomerAccountBindingUseCase bindings,
            AuthorizeCustomerOwnedResourceActionUseCase authorization,
            CreateOrderUseCase createOrderUseCase) {

        return new CreateCustomerOrderService(
                bindings,
                authorization,
                createOrderUseCase);
    }
    @Bean
    ViewCustomerOrderUseCase viewCustomerOrderUseCase(
            OrderRepository orderRepository,
            ResolveCustomerAccountBindingUseCase bindings,
            AuthorizeCustomerOwnedResourceActionUseCase authorization) {

        return new ViewCustomerOrderService(
                orderRepository,
                bindings,
                authorization);
    }
}
