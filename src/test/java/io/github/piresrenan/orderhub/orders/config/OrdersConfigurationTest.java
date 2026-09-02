package io.github.piresrenan.orderhub.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedCreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedCreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer.MicrometerObservedTransactionExecutor;
import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlOrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OrdersConfigurationTest {

    @Autowired
    private CreateOrderUseCase createOrderUseCase;

    @Autowired
    private OrderIdGenerator orderIdGenerator;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CreateOrderIdempotencyRepository idempotencyRepository;

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Test
    void wiresOrderApplicationComponentsThroughObservedBoundaries() {

        assertThat(createOrderUseCase)
                .isInstanceOf(
                        MicrometerObservedCreateOrderUseCase.class);

        assertThat(orderRepository)
                .isInstanceOf(
                        PostgreSqlOrderRepository.class);

        assertThat(idempotencyRepository)
                .isInstanceOf(
                        MicrometerObservedCreateOrderIdempotencyRepository.class);

        assertThat(orderIdGenerator)
                .isNotNull();

        assertThat(transactionExecutor)
                .isInstanceOf(
                        MicrometerObservedTransactionExecutor.class);
    }
}
