package io.github.piresrenan.orderhub.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;


import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql.PostgreSqlOrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class OrdersConfigurationTest {

    @Autowired
    private CreateOrderUseCase createOrderUseCase;

    @Autowired
    private OrderIdGenerator orderIdGenerator;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void wiresOrderApplicationComponents() {
        // Why: correct classes may still fail at runtime if Spring composition is
        // wrong.
        // Covers: availability and concrete wiring of the order use case and output
        // ports.
        // Prevents: missing beans, ambiguous dependencies and accidental adapter
        // replacement.
        assertThat(createOrderUseCase).isNotNull();
        assertThat(orderRepository).isInstanceOf(PostgreSqlOrderRepository.class);
        assertThat(orderIdGenerator).isNotNull();
    }
}
