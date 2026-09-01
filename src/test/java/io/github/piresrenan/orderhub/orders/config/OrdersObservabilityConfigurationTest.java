package io.github.piresrenan.orderhub.orders.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedCreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedTransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;

class OrdersObservabilityConfigurationTest {

    @Test
    void compositionRootWrapsUseCaseAndTransactionBoundaryWithMicrometer() {

        var configuration =
                new OrdersConfiguration();

        var registry =
                new SimpleMeterRegistry();

        var transactionExecutor =
                configuration.transactionExecutor(
                        mock(
                                PlatformTransactionManager.class),
                        new OrderTransactionProperties(
                                Duration.ofSeconds(5)),
                        registry);

        assertThat(transactionExecutor)
                .isInstanceOf(
                        MicrometerObservedTransactionExecutor.class);

        OrderIdGenerator orderIdGenerator =
                UUID::randomUUID;

        var createOrder =
                configuration.createOrderUseCase(
                        mock(
                                OrderRepository.class),
                        orderIdGenerator,
                        transactionExecutor,
                        mock(
                                ValidateOrderableVariantsUseCase.class),
                        mock(
                                CommitOrderInventoryUseCase.class),
                        registry);

        assertThat(createOrder)
                .isInstanceOf(
                        MicrometerObservedCreateOrderUseCase.class);
    }
}
